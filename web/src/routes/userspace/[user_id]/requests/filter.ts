import jsonata, {type Expression} from "jsonata";
import type {RequestKind, RequestUpdate} from "../state.ts";

export interface RequestsFilter {
    filter_methods: string[],
    only_websockets: boolean
}

export function defaultFilter(): RequestsFilter {
    return {
        filter_methods: [],
        only_websockets: false,
    }
}

/** Query of an inactive filter: keeps every request, so filtering has a single code path. */
const NEUTRAL_QUERY = "[$]"

const METHODS_PREDICATE = /^method in \[(.*)]$/

const WEBSOCKET_KIND: RequestKind = "websocket"

// Matches on the request kind, not on the 101 handshake: an upgrade the upstream answers with an
// HTTP error is still a WebSocket request and has to stay visible under this filter.
const WEBSOCKET_PREDICATE = `kind = ${literal(WEBSOCKET_KIND)}`

/** Matches a whole query and captures its predicate body, which is absent for the neutral query. */
const QUERY = /^\[\$(?:\[(.*)])?]$/

const STRING_LITERAL = /^"((?:[^"\\]|\\.)*)"$/

/** Quotes a value as a JSONata string literal. */
function literal(value: string): string {
    return `"${value.replace(/\\/g, "\\\\").replace(/"/g, "\\\"")}"`
}

/** Reverse of `literal`; `null` if the input is not a single string literal. */
function parseLiteral(input: string): string | null {
    const match = STRING_LITERAL.exec(input.trim())
    if (!match) return null
    return match[1].replace(/\\(.)/g, "$1")
}

/** Splits on `separator`, skipping occurrences inside string literals. */
function splitOutsideLiterals(input: string, separator: string): string[] {
    const parts: string[] = []
    let current = ""
    let inLiteral = false

    for (let i = 0; i < input.length; i++) {
        const char = input[i]
        if (inLiteral) {
            current += char
            if (char === "\\") current += input[++i] ?? ""
            else if (char === "\"") inLiteral = false
        } else if (char === "\"") {
            current += char
            inLiteral = true
        } else if (input.startsWith(separator, i)) {
            parts.push(current)
            current = ""
            i += separator.length - 1
        } else {
            current += char
        }
    }

    parts.push(current)
    return parts
}

/** One JSONata predicate per active part of the filter; inactive parts contribute nothing. */
function predicates(filter: RequestsFilter): string[] {
    const parts: string[] = []

    if (filter.filter_methods.length > 0) {
        parts.push(`method in [${filter.filter_methods.map(literal).join(", ")}]`)
    }

    if (filter.only_websockets) {
        parts.push(WEBSOCKET_PREDICATE)
    }

    return parts
}

/**
 * Builds the JSONata query that maps the full request list onto the matching subset.
 * An inactive filter yields the neutral query instead of a special case.
 */
export function buildRequestQuery(filter: RequestsFilter): string {
    const parts = predicates(filter)
    if (parts.length === 0) return NEUTRAL_QUERY
    // Outer brackets keep the result an array even for zero or one match.
    return `[$[${parts.join(" and ")}]]`
}

/**
 * Reverse of `buildRequestQuery`, needed because the query is what gets stored in the URL:
 * the filter UI has to show which parts are active. Predicates that this module did not build
 * (hand-edited URL) are dropped rather than rejecting the whole query.
 */
export function parseRequestQuery(query: string | null | undefined): RequestsFilter {
    const filter = defaultFilter()
    const body = query ? QUERY.exec(query.trim())?.[1] : undefined
    if (!body) return filter

    for (const predicate of splitOutsideLiterals(body, " and ")) {
        if (predicate.trim() === WEBSOCKET_PREDICATE) {
            filter.only_websockets = true
            continue
        }

        const list = METHODS_PREDICATE.exec(predicate.trim())?.[1]
        if (list === undefined) continue

        const methods = splitOutsideLiterals(list, ",").map(parseLiteral)
        if (methods.some(method => method === null)) continue
        filter.filter_methods = [...new Set([...filter.filter_methods, ...methods as string[]])]
    }

    return filter
}

export function isDefaultFilter(filter: RequestsFilter): boolean {
    return buildRequestQuery(filter) === NEUTRAL_QUERY
}

export function filterToParams(filter: RequestsFilter): URLSearchParams {
    const params = new URLSearchParams()
    params.set("q", buildRequestQuery(filter))
    return params
}

export function filterFromParams(params: URLSearchParams): RequestsFilter {
    return parseRequestQuery(params.get("q"))
}

// Compiling a JSONata expression is the expensive part, so keep one per query string.
const compiled = new Map<string, Expression>()

function expression(query: string): Expression {
    let expr = compiled.get(query)
    if (!expr) {
        expr = jsonata(query)
        compiled.set(query, expr)
    }
    return expr
}

export async function runRequestQuery(query: string, requests: RequestUpdate[]): Promise<RequestUpdate[]> {
    const result = await expression(query).evaluate(requests)
    return (result ?? []) as RequestUpdate[]
}
