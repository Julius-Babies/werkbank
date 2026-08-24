import type {SourceLanguage} from "./languages.ts";

/**
 * Which language a body is highlighted as. `Content-Type` decides; the path is only asked when the
 * header says nothing useful, which is common enough to be worth it — a bundler serving its chunks
 * as `application/octet-stream` is not an exotic setup.
 *
 * JSON is deliberately absent: the detail view has a structured viewer for it.
 */

const MEDIA_TYPES: Record<string, SourceLanguage> = {
    "text/html": "html",
    "application/xhtml+xml": "html",
    "application/xml": "html",
    "text/xml": "html",
    "text/css": "css",
    "text/javascript": "js",
    "application/javascript": "js",
    "application/x-javascript": "js",
    "application/ecmascript": "js",
    "text/ecmascript": "js",
    "text/jsx": "jsx",
    "text/typescript": "ts",
    "application/typescript": "ts",
    "text/x-typescript": "ts",
    "text/tsx": "tsx",
    "text/markdown": "markdown",
    "text/x-markdown": "markdown",
    "application/yaml": "yaml",
    "text/yaml": "yaml",
    "text/x-yaml": "yaml",
    "application/toml": "toml",
    "text/x-toml": "toml",
    "application/sql": "sql",
    "text/x-sql": "sql",
    "text/x-python": "python",
    "application/x-python-code": "python",
    "text/x-sh": "shell",
    "application/x-sh": "shell",
    "application/x-shellscript": "shell",
    "text/vue": "vue",
}

const EXTENSIONS: Record<string, SourceLanguage> = {
    css: "css",
    htm: "html",
    html: "html",
    js: "js",
    jsx: "jsx",
    md: "markdown",
    mjs: "js",
    cjs: "js",
    py: "python",
    sh: "shell",
    sql: "sql",
    svelte: "svelte",
    toml: "toml",
    ts: "ts",
    tsx: "tsx",
    vue: "vue",
    xml: "html",
    yaml: "yaml",
    yml: "yaml",
}

/** The media type without its parameters, e.g. `text/html` for `text/html; charset=utf-8`. */
function mediaType(contentType: string): string {
    return contentType.split(";")[0].trim().toLowerCase()
}

/** The extension of the path part of a URI, without query or fragment. */
function extension(uri: string): string | null {
    const path = uri.split(/[?#]/, 1)[0]
    const name = path.slice(path.lastIndexOf("/") + 1)
    const dot = name.lastIndexOf(".")
    return dot > 0 ? name.slice(dot + 1).toLowerCase() : null
}

export function languageOf(contentType: string | null, uri: string): SourceLanguage | null {
    if (contentType) {
        const type = mediaType(contentType)

        // An image is previewed as an image, never as source — including SVG, which the suffix rule
        // below would otherwise claim as markup.
        if (type.startsWith("image/")) return null

        const known = MEDIA_TYPES[type]
        if (known) return known

        // Structured syntax suffixes: anything `+xml` is markup, whatever the vendor prefix says.
        if (type.endsWith("+xml")) return "html"

        // A body the header calls plain text is shown as plain text, even if the path suggests more:
        // the server is explicitly saying it has no structure.
        if (type === "text/plain") return null
    }

    return (EXTENSIONS[extension(uri) ?? ""] ?? null)
}
