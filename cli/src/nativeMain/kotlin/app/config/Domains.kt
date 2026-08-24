package app.config

import app.storage.isDevMode

/**
 * Base domain for projects managed by werkbank. A project without an explicit
 * `externalDomain` is reachable at `<project-id>.[DEFAULT_BASE_DOMAIN]`.
 *
 * In dev mode the label carries a `dev` suffix (`wbdev.local`) so a dev CLI never
 * collides with the domains of a production installation.
 */
val DEFAULT_BASE_DOMAIN by lazy {
    buildString {
        append("wb")
        if (isDevMode) append("dev")
        append(".local")
    }
}

/**
 * Base domain for werkbank's own services and dependencies
 * (Traefik dashboard, Keycloak, Postgres, MongoDB, ...).
 *
 * Dev mode suffix as in [DEFAULT_BASE_DOMAIN] (`werkbankdev.local`).
 */
val WERKBANK_BASE_DOMAIN by lazy {
    buildString {
        append("werkbank")
        if (isDevMode) append("dev")
        append(".local")
    }
}
