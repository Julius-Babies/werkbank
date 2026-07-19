# Umsetzungsplan: Lifecycle + Reverse-Proxy-Abstraktion

Branch: `23-rebuild-proxy-configuration`

## Kontext / Ist-Zustand

Die CLI verwaltet Infrastruktur-Dependencies über das Interface
`AppDependency` (`cli/src/nativeMain/kotlin/app/dependencies/AppDependency.kt`).
Aktuelle Probleme:

- **`initialize()` ist überladen:** schreibt Config, legt Container an *und*
  führt Laufzeitarbeit im laufenden Container aus (`withRunning`, DB-Anlage,
  Keycloak-Realm, RabbitMQ-vhosts, Unbound-Keygen). Widerspricht dem eigenen
  Doc-Comment ("should not require the container to be running").
- **`start()` überschneidet sich mit `initialize()`:** `start(createIfNotExists = true)`
  legt den Container ggf. selbst an → Grenze verschwimmt.
- **Kein `update()`-Konzept für Dependencies:** `commands/update` ist reines
  CLI-Self-Update. `DockerContainer.create()` pullt nur, wenn das Image lokal
  fehlt; `needsRebuild()` prüft nur Netzwerk-Aliase, nicht den Image-Tag. Der
  einzige echte Image-Update-Pfad ist der Keycloak-Sonderfall in
  `ConfigCommand.replaceKeycloakContainer()`.
- **Commands umgehen die Abstraktion:** `DownCommand` kennt nur Postgres/Traefik
  hart kodiert; `ConfigCommand` greift direkt auf `keycloak.getContainer()` /
  `postgres18.container` zu.
- **Versteckte Kopplung:** `Keycloak.initialize()` ruft direkt
  `postgres18.initialize()/start()`. Reihenfolge sonst nur implizit über die
  Listenreihenfolge in `Main.kt`.
- **Traefik-Eigenheiten lecken:** Die Übersetzung `project.http` → Traefik-Regeln
  (`Host()`, `PathPrefix()`, `HostRegexp`, `dynamic/*.yaml`) steckt in
  `TraefikManager.generateProxyConfig()` / `createInternalServices()` /
  `generateSslConfig()`.

**Bereits vorhanden auf diesem Branch:** `ReverseProxyConfiguration.kt`
(provider-neutrales Routing-Modell mit `Group` / `Route` / `Target`) — ein
noch nirgends verdrahteter Stub, gedacht als Zielmodell.

**Keine Tests vorhanden.** Absicherung = nativer Compile/Link + manueller Diff
der generierten `traefik/dynamic/*.yaml`.

## Prinzipien

- **Neutral vor spezifisch:** `ReverseProxyConfiguration` ist das
  provider-unabhängige Modell. Traefik-Begriffe leben nur noch hinter einem
  `ReverseProxy`-Interface.
- **Verhaltensgleichheit pro Schritt:** Jeder Schritt kompiliert und erzeugt
  identische Traefik-Dateien wie heute.
- **Kleine Schritte:** Nach jedem Schritt nativer Build (kein Testnetz).

## Vorab zu entscheidende Modell-Erweiterungen

Der Stub deckt noch nicht alles ab, was `TraefikManager` heute erzeugt:

| Fehlt im Stub | Woher heute | Vorschlag |
|---|---|---|
| `priority` | `httpEntry.priority` (TraefikManager:291) | `Route.priority: Int? = null` |
| Beschreibungen/Kommentare | `descriptions` → YAML-Kommentar | `Route.descriptions: List<String>` (oder pro Group) |
| Wildcard-Hosts `*.` / `**.` | `toTraefikHostRule()` (Regexp) | `hosts` von `List<String>` auf `List<HostMatcher>` mit `Exact` / `WildcardSingle` / `WildcardMulti` heben |
| TLS-Zertifikate | `generateSslConfig()` (Projekt-, Dependency-, externe Certs) | zweite Liste `certificates: List<Certificate>` neben `groups` |
| Upstream „lokal“ | `host.docker.internal` (Local-Mode) | schon abgedeckt durch `Target.Host(port)` |

**Offene Entscheidungen:**

1. Wildcard-Hosts als eigener `HostMatcher`-Typ (empfohlen: sauber) vs. rohe
   Pattern-Strings im Modell (schnell, leakt aber Traefik-Semantik).
2. Zertifikate ins `ReverseProxyConfiguration` integrieren vs. getrennter Kanal
   an `apply()`.
3. PR-Zuschnitt: Lifecycle-PR (Phase 1+2) + Proxy-PR (Phase 4+5) getrennt.

---

## Phase 1 — Lifecycle-Split (kein Verhaltenswechsel)

**Ziel:** `initialize()` in klare Phasen zerlegen.

`AppDependency.kt` — Interface erweitern:

```kotlin
interface AppDependency {
    val key: String
    fun isRequiredFor(project: Project): Boolean
    fun isAlwaysRequired(): Boolean = false

    /** Explizite Reihenfolge statt versteckter Kopplung (Keycloak → Postgres). */
    val dependsOn: List<String> get() = emptyList()

    /** Nur Dateien/Config/Verzeichnisse. Idempotent, billig, KEIN Container läuft. */
    suspend fun configure() {}

    /** Container anlegen + Image ziehen falls nötig. Kein Start. */
    suspend fun provision()

    /** Nur starten. */
    suspend fun start()

    /** Laufzeitarbeit, die laufende Container braucht (DB anlegen, Realm, vhosts). */
    suspend fun ensureReady() {}

    suspend fun stop()

    /** Image neu ziehen / Container neu bauen, wenn sich Tag/Digest geändert hat. */
    suspend fun update()
}
```

Pro Implementierung Inhalt verschieben:

- **Postgres18:** `createProjectDatabases()` → `ensureReady()`; `create()` →
  `provision()`; mkdir/hosts → `configure()`.
- **Keycloak:** `ensureKeycloakDatabase()` → `ensureReady()`;
  `postgres18.initialize()/start()`-Aufruf **entfernen**, stattdessen
  `dependsOn = ["postgres18"]`; `create()` → `provision()`.
- **Unbound:** Keygen-Throwaway-Container → `ensureReady()`; `writeConfigFile()`
  → `configure()`; enabled-Check an eine Stelle.
- **RabbitMq:** vhost-Setup → `ensureReady()`.
- **MongoDb / Jaeger:** mkdir/hosts → `configure()`, `create()` → `provision()`.
  Nebenbei den `Jaeger.stop()`-Log-Bug fixen ("Stopping Unbound" → "Jaeger").

`start()` verliert die `createIfNotExists`-Semantik (Aufrufer rufen vorher
`provision()`).

**Risiko:** Reihenfolge Keycloak↔Postgres — wird in Phase 2 durch Topo-Sort
korrekt; bis dahin Reihenfolge in `Main.kt`-Liste beibehalten.

## Phase 2 — Orchestrator

**Neu:** `app/dependencies/DependencyOrchestrator.kt`

- `up(project: Project?)`: benötigte Deps per
  `isAlwaysRequired()/isRequiredFor()` filtern, topologisch nach `dependsOn`
  sortieren, dann `configure → provision → start → ensureReady`.
- `down(project)`: generische Ref-Count-Logik (aus `DownCommand` extrahiert, für
  **alle** Deps statt nur Postgres/Traefik).
- `poweroff()`, `update(keys)`.

Commands umstellen:

- `UpCommand`: beide Schleifen → `orchestrator.up(...)`; Doppel-Init bei
  `--start-infrastructure` verschwindet.
- `DownCommand`: → `orchestrator.down(project)` (ad-hoc Postgres/Traefik-Logik
  entfällt).
- `PoweroffCommand`: → `orchestrator.poweroff()`.
- `SetupCommand`: Keycloak-Sonderweg über Orchestrator/`ensureReady` +
  bestehendes `ensureRealm`.
- `Main.kt`: `DependencyOrchestrator` als `single` registrieren.

## Phase 3 — `update()` + Image-Vergleich

- `DockerContainer.needsRebuild()` (DockerContainer.kt:139) zusätzlich
  Image-Tag/-Digest vergleichen (heute nur Netzwerk-Aliase).
- `AppDependency.update()` default: `provision()` mit erzwungenem Rebuild bei
  Image-Änderung + Re-Pull.
- `ConfigCommand.replaceKeycloakContainer()` durch generischen Pfad ersetzen
  (Keycloak-Sonderfall wird Normalfall).
- Optional: `wb dependency update [<key>]`-Command.

## Phase 4 — Routing-Modell + Resolver

- `ReverseProxyConfiguration` um die o.g. Felder erweitern (`priority`,
  `descriptions`, `HostMatcher`, `certificates`).
- **Neu:** `app/dependencies/reverse_proxy/RouteResolver.kt` — verschiebt die
  Logik aus `TraefikManager.generateProxyConfig()` + `createInternalServices()`
  + `generateSslConfig()` **1:1** hierher und liefert
  `ReverseProxyConfiguration`. Reine Datenaufbereitung, kein YAML.
- `TraefikManager` konsumiert vorerst intern `RouteResolver` und rendert daraus
  dieselben Dateien → Diff der Ausgabedateien muss leer sein.

## Phase 5 — `ReverseProxy`-Interface

- **Neu:** `app/dependencies/reverse_proxy/ReverseProxy.kt`:
  `interface ReverseProxy : AppDependency { suspend fun apply(config: ReverseProxyConfiguration) }`.
- `TraefikManager` → `TraefikReverseProxy : ReverseProxy`; sämtliche
  YAML-Erzeugung landet in `apply(...)`. `configure()` = `apply(resolver.resolve())`.
- `Main.kt`: DI auf `single<ReverseProxy> { TraefikReverseProxy(...) }`
  umstellen; die zwei direkten `TraefikManager`-Injektionen (`DownCommand`,
  `reverse_proxy/RebuildCommand`) auf das Interface heben. `RebuildCommand`
  provider-neutral machen.
- **Ergebnis:** Ein Caddy/nginx-Provider bräuchte nur `apply(...)` + Lifecycle;
  Projekte/Dependencies bleiben unberührt.

## Phase 6 (optional) — Querschnitt zentralisieren

- `/etc/hosts`-Einträge und Cert-Ausstellung aus dem neutralen Modell ableiten
  (Orchestrator), statt verstreute `hostsManager.addHost(...)` in jeder
  `configure()`. `OpensslHandler` greift dann nicht mehr selbst in die
  Dep-Liste.

---

## Empfohlene Reihenfolge & Zuschnitt

Phasen 1→2→3 sind unabhängig vom Proxy und liefern sofort Wert (sauberer
Lifecycle, `update()`). Phasen 4→5 sind das Kernanliegen und bauen auf dem
`ReverseProxyConfiguration`-Stub auf. Phase 6 ist Kür.

**Vorschlag:**

- **PR 1:** Phase 1 + 2 (Lifecycle-Split + Orchestrator)
- **PR 2:** Phase 4 + 5 (Reverse-Proxy-Abstraktion)
- Phase 3 dazwischen oder danach.
