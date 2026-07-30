# AGENTS.md

## Project overview

Werkbank brings `localhost` to the web: static domains, SSL and access control for local development
services. You describe your services in a `Werkbankfile.yaml`, run `wb setup` (generates domains + SSL
certificates, locally, no cloud required) and reach them under e.g. `myproject.werkbank.space`.
Optionally shareable with an account on https://wbspace.app; `wb tunnel` makes the proxy reachable
from anywhere.

It is a Gradle multi-module project (Kotlin) plus a standalone SvelteKit web UI.

## Modules

Gradle modules (in `settings.gradle.kts`):

- **`cli/`** — The `wb` CLI. Kotlin/Native (targets: macOS arm64, Linux x64/arm64), Compose Runtime +
  Mosaic for the TUI, Clikt for commands (`cli/src/nativeMain/kotlin/commands/`: `setup`, `up`, `down`,
  `tunnel`, `login`, `cloud`, `service`, `dependencies`, `update`, …). Currently only macOS is
  supported, Linux is in progress.
- **`api/`** — Server backend (JVM). Ktor (Netty) + Koin, Exposed/PostgreSQL, OpenTelemetry/Jaeger,
  ACME for certificates. Contains Proxy, Auth, Projects, Certificates, Tunnel and the WebApp API
  (`app/webapp/`).
- **`shared/`** — Multiplatform module (JVM + Native) with the models/serialization shared between CLI
  and API (`shared/src/commonMain`).

Not a Gradle module, but part of the repo:

- **`web/`** — Web UI (Userspace / Cloud UI). SvelteKit + Svelte 5, Tailwind v4, shadcn-svelte, Bun.
- **`example-webapp/`** — Example application for testing the proxy.
- **`deploy/`**, **`Dockerfile`** — Container setup (Temurin JRE + Caddy + Bun) for deployment.
- **`data/`** — Local runtime data (certificates, CLI binaries, `config.json`).

## Dev environment & commands

```bash
./gradlew :api:fatJar       # Build the server jar
./gradlew :cli:build        # Build the CLI
cd web && bun install       # Install web dependencies
cd web && bun run check     # Type-check the web UI
```

- **All web projects use Bun** — never use `npm`, `pnpm` or `yarn`.
- Server ports per `Werkbankfile.yaml`: `api` → 7010, `ui` → 7020.
- `local.properties` provides the CLI with `cli.version`, `cli.variant`, `cli.dev` (via buildkonfig).

## Agent rules

- **Never start anything.** Do not run servers, dev servers or long-running processes
  (`./gradlew :api:run`, `bun run dev`, `wb up`, `wb tunnel`, etc.).
- If something relevant is offline (API server, web dev server, a dependency like Postgres/Jaeger),
  **ask the user to start it** instead of starting it yourself.
- Always run type checks after changing web code (`cd web && bun run check`).

## Testing

```bash
./gradlew :api:test         # JVM tests (Ktor test host)
./gradlew test              # All module tests
```

## Code style

- Kotlin package root: `app.werkbank`. Official Kotlin code style (`kotlin.code.style=official`).
- JVM toolchain 26.
- Web: TypeScript + Svelte 5 runes, Tailwind v4, shadcn-svelte components.
- Comments: Always english, do not overly comment

### Web
- API actions shall be in a repository to separate them from the UI.
- Always use i18n translation. Use clear keys, not english strings, only qualifiers. If working on a feature where strings are not i18n, refactor them only if you are working on that piece.

## PR & commit guidelines

- Commit messages follow this rule:
  (feat|fix|chore|docs|...)((web/|cli/|api/)<component> #<issue_id>): <comment> - e.g. feat(api,web/projects #59): Add delete projects endpoint and delete project dialog
- If it enhances traceability, then split multiple changes into separate commits
