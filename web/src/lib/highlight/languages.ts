import type {LanguageDefinition} from "@tanstack/highlight/core";

/** The languages a proxied body can be previewed as. */
export type SourceLanguage =
    | "css"
    | "html"
    | "js"
    | "jsx"
    | "markdown"
    | "python"
    | "shell"
    | "sql"
    | "svelte"
    | "toml"
    | "ts"
    | "tsx"
    | "vue"
    | "yaml"

/**
 * One dynamic import per grammar. This is the point of the whole file: a response body pulls in the
 * grammar it is highlighted with and nothing else, instead of the barrel export that would bundle
 * all twenty-six of them into the page.
 */
const GRAMMARS: Record<SourceLanguage, () => Promise<LanguageDefinition>> = {
    css: () => import("@tanstack/highlight/languages/css").then((module) => module.css),
    html: () => import("@tanstack/highlight/languages/html").then((module) => module.html),
    js: () => import("@tanstack/highlight/languages/js").then((module) => module.js),
    jsx: () => import("@tanstack/highlight/languages/jsx").then((module) => module.jsx),
    markdown: () => import("@tanstack/highlight/languages/markdown").then((module) => module.markdown),
    python: () => import("@tanstack/highlight/languages/python").then((module) => module.python),
    shell: () => import("@tanstack/highlight/languages/shell").then((module) => module.shell),
    sql: () => import("@tanstack/highlight/languages/sql").then((module) => module.sql),
    svelte: () => import("@tanstack/highlight/languages/svelte").then((module) => module.svelte),
    toml: () => import("@tanstack/highlight/languages/toml").then((module) => module.toml),
    ts: () => import("@tanstack/highlight/languages/ts").then((module) => module.ts),
    tsx: () => import("@tanstack/highlight/languages/tsx").then((module) => module.tsx),
    vue: () => import("@tanstack/highlight/languages/vue").then((module) => module.vue),
    yaml: () => import("@tanstack/highlight/languages/yaml").then((module) => module.yaml),
}

/**
 * Grammars that hand parts of a document to another grammar: the markup ones tokenize `<style>` and
 * `<script>` contents as CSS, JS or TS. A highlighter without them still works — the tokenizer asks
 * `hasLanguage` first and leaves the region alone — but a stylesheet inside a page would stay grey.
 */
const EMBEDDED: Partial<Record<SourceLanguage, SourceLanguage[]>> = {
    html: ["css", "js", "ts"],
    svelte: ["css", "js", "ts"],
    vue: ["css", "js", "ts"],
}

/** Every grammar a highlighter for [language] needs, the language itself first. */
export function grammarsFor(language: SourceLanguage): Promise<LanguageDefinition[]> {
    return Promise.all([language, ...(EMBEDDED[language] ?? [])].map((name) => GRAMMARS[name]()))
}
