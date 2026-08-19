import type {RequestKind} from "../state.ts";
import {parseQuery, type QueryTerm, serializeQuery} from "./query.ts";

/** Request methods offered as filter buttons. */
export const FILTER_METHODS = ["GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"]

const WEBSOCKET_KIND: RequestKind = "websocket"

/** The part of a query the filter buttons can express. */
export interface RequestsFilter {
    filter_methods: string[],
    only_websockets: boolean
}

function defaultFilter(): RequestsFilter {
    return {
        filter_methods: [],
        only_websockets: false,
    }
}

function filterToTerms(filter: RequestsFilter): QueryTerm[] {
    const terms: QueryTerm[] = []

    if (filter.filter_methods.length > 0) {
        terms.push({qualifier: "method", values: filter.filter_methods, negated: false})
    }

    // Matches on the request kind, not on the 101 handshake: an upgrade the upstream answers with
    // an HTTP error is still a WebSocket request and has to stay visible under this filter.
    if (filter.only_websockets) {
        terms.push({qualifier: "is", values: [WEBSOCKET_KIND], negated: false})
    }

    return terms
}

export interface RequestQuery {
    /** The query as stored in the URL and shown in the filter bar. */
    query: string,
    /** Filter parts recovered from the query; complete unless `advanced`. */
    filter: RequestsFilter,
    /** The query does more than the filter buttons can express, so they cannot edit it. */
    advanced: boolean
}

export function queryFromFilter(filter: RequestsFilter): RequestQuery {
    return {query: serializeQuery(filterToTerms(filter)), filter, advanced: false}
}

export function neutralQuery(): RequestQuery {
    return queryFromFilter(defaultFilter())
}

/**
 * Recovers the button state from a query. Anything a button could not have produced — a negation,
 * an unknown qualifier, free text, a method without a button, a second term for the same button —
 * marks the query as `advanced`: it still runs, but the buttons only display it.
 */
export function queryFromString(query: string | null | undefined): RequestQuery {
    const terms = parseQuery(query ?? "")
    const filter = defaultFilter()
    let advanced = false

    for (const term of terms) {
        if (term.negated) {
            advanced = true
            continue
        }

        if (term.qualifier === "method" && filter.filter_methods.length === 0) {
            const methods = term.values.map((value) => value.toUpperCase())
            if (methods.every((method) => FILTER_METHODS.includes(method))) {
                filter.filter_methods = methods
                continue
            }
        }

        if (term.qualifier === "is" && !filter.only_websockets
            && term.values.length === 1 && term.values[0].toLowerCase() === WEBSOCKET_KIND) {
            filter.only_websockets = true
            continue
        }

        advanced = true
    }

    // A query the buttons can express is normalized, so the executed query, the URL and the state
    // of the buttons can never drift apart. An advanced query is kept as written, apart from
    // normalized whitespace and quoting.
    return advanced ? {query: serializeQuery(terms), filter, advanced} : queryFromFilter(filter)
}

/** True when nothing is filtered out, i.e. when resetting would change nothing. */
export function isNeutralQuery(state: RequestQuery): boolean {
    return state.query.length === 0
}

export function queryToParams(state: RequestQuery): URLSearchParams {
    const params = new URLSearchParams()
    params.set("q", state.query)
    return params
}

export function queryFromParams(params: URLSearchParams): RequestQuery {
    return queryFromString(params.get("q"))
}
