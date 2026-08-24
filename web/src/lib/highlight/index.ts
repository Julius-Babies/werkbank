import type {Highlighter, HighlightToken} from "@tanstack/highlight/core";
import {grammarsFor, type SourceLanguage} from "./languages.ts";

export {languageOf} from "./contentType.ts";
export type {SourceLanguage} from "./languages.ts";
export type {HighlightToken} from "@tanstack/highlight/core";

/**
 * Bodies past this are shown unhighlighted. Tokenizing runs on the main thread at roughly a
 * millisecond per kilobyte, and a bundle nobody is going to read line by line is not worth a frozen
 * tab. The token cap catches the same thing from the other side: minified code is small but
 * tokenizes into a span per character run, and rendering those is what actually gets expensive.
 */
const MAX_SOURCE_CHARS = 128 * 1024
const MAX_TOKENS = 15_000

// One highlighter per language, built the first time a body needs it and kept for the session.
const highlighters = new Map<SourceLanguage, Promise<Highlighter>>()

function highlighterFor(language: SourceLanguage): Promise<Highlighter> {
    let pending = highlighters.get(language)

    if (!pending) {
        // The engine is imported here rather than at the top of the module so that a request whose
        // body is not source — the common case — never loads a byte of it.
        pending = Promise.all([import("@tanstack/highlight/core"), grammarsFor(language)]).then(
            ([{createHighlighter}, languages]) =>
                // The one language is its own fallback, so an unknown `lang` can never silently
                // produce a plaintext highlighter that this instance carries no grammar for.
                createHighlighter({languages, fallbackLanguage: language}),
        )
        highlighters.set(language, pending)
    }

    return pending
}

/**
 * Tokenizes a body, or returns `null` when it is too large to be worth highlighting — the caller
 * shows it as plain text then. Loading the grammar is what makes this asynchronous.
 */
export async function tokenizeSource(
    code: string,
    language: SourceLanguage,
): Promise<HighlightToken[] | null> {
    if (code.length > MAX_SOURCE_CHARS) return null

    const highlighter = await highlighterFor(language)
    const {tokens} = highlighter.tokenize(code, {lang: language})

    return tokens.length > MAX_TOKENS ? null : tokens
}
