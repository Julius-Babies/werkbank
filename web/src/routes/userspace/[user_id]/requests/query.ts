import jexl from "jexl";
import type {RequestKind, RequestUpdate} from "../state.ts";

/**
 * The filter query language, a small subset of GitHub's issue search syntax:
 *
 *   method:GET,POST     qualifier with comma separated values, the term matches any of them
 *   is:websocket        request kind, `http` or `websocket`
 *   status:500,502      response status code
 *   project:web         target project
 *   service:api         target service
 *   "user profile"      free text, matched against the request URI
 *   -method:GET         negated term
 *
 * Terms are separated by whitespace and combined with AND; values may be quoted to include
 * whitespace, commas or colons. A query is compiled into a JEXL expression and evaluated once
 * per request, with the request itself as the evaluation context.
 */

export interface QueryTerm {
    /** Qualifier name, or `null` for a free text term. */
    qualifier: string | null,
    /** Values of the term; the term matches a request if any of them matches. */
    values: string[],
    negated: boolean
}

export type RequestMatcher = (request: RequestUpdate) => boolean

const KINDS: RequestKind[] = ["http", "websocket"]

/** Expression for a term that cannot match anything, e.g. an unknown qualifier. */
const NEVER = "false"

const engine = new jexl.Jexl()
engine.addTransform("lower", (value: unknown) => String(value ?? "").toLowerCase())

/** JEXL string literals are JSON string literals. */
function literal(value: string): string {
    return JSON.stringify(value)
}

function membership(path: string, values: string[]): string {
    return `${path} in [${values.map(literal).join(", ")}]`
}

const QUALIFIERS: Record<string, (values: string[]) => string> = {
    method: (values) => membership("method", values.map((value) => value.toUpperCase())),
    is: (values) => {
        const kinds = values.map((value) => value.toLowerCase())
        if (!kinds.every((kind) => (KINDS as string[]).includes(kind))) return NEVER
        return membership("kind", kinds)
    },
    status: (values) => {
        const codes = values.map(Number)
        if (!codes.every(Number.isInteger)) return NEVER
        return `status_code in [${codes.join(", ")}]`
    },
    project: (values) => membership("target.project_name", values),
    service: (values) => membership("target.service_name", values),
}

/** Free text is a case insensitive substring match on the request URI. */
function freeText(values: string[]): string {
    return values
        .map((value) => `${literal(value.toLowerCase())} in uri|lower`)
        .join(" || ")
}

/** Splits a query into its terms, keeping quoted sections intact. */
function tokenize(query: string): string[] {
    const tokens: string[] = []
    let current = ""
    let quoted = false

    for (const char of query) {
        if (char === "\"") {
            quoted = !quoted
            current += char
        } else if (!quoted && /\s/.test(char)) {
            if (current) tokens.push(current)
            current = ""
        } else {
            current += char
        }
    }

    if (current) tokens.push(current)
    return tokens
}

function indexOfUnquoted(input: string, char: string): number {
    let quoted = false
    for (let i = 0; i < input.length; i++) {
        if (input[i] === "\"") quoted = !quoted
        else if (input[i] === char && !quoted) return i
    }
    return -1
}

/** Splits the value list of a term on commas and strips the quotes around individual values. */
function splitValues(input: string): string[] {
    const values: string[] = []
    let current = ""
    let quoted = false

    for (const char of input) {
        if (char === "\"") quoted = !quoted
        else if (char === "," && !quoted) {
            values.push(current)
            current = ""
        } else current += char
    }

    values.push(current)
    return values.filter((value) => value.length > 0)
}

function unquote(input: string): string {
    return input.replaceAll("\"", "")
}

export function parseQuery(query: string): QueryTerm[] {
    return tokenize(query)
        .map((token) => {
            const negated = token.startsWith("-")
            const body = negated ? token.slice(1) : token
            const colon = indexOfUnquoted(body, ":")

            // A leading colon or none at all makes the whole token free text, which is never split
            // on commas: a comma is a plain character in a search phrase.
            if (colon <= 0) return {qualifier: null, values: [unquote(body)], negated}

            return {
                qualifier: body.slice(0, colon).toLowerCase(),
                values: splitValues(body.slice(colon + 1)),
                negated,
            }
        })
        .filter((term) => term.values.length > 0)
}

function quoteValue(value: string): string {
    return /[\s,:"]/.test(value) ? `"${unquote(value)}"` : value
}

/** Inverse of `parseQuery`; the result parses back into the terms it was built from. */
export function serializeQuery(terms: QueryTerm[]): string {
    return terms
        .map((term) => {
            const values = term.values.map(quoteValue).join(",")
            const prefix = term.negated ? "-" : ""
            return term.qualifier === null ? `${prefix}${values}` : `${prefix}${term.qualifier}:${values}`
        })
        .join(" ")
}

function compileTerm(term: QueryTerm): string {
    const compile = term.qualifier === null ? freeText : QUALIFIERS[term.qualifier]
    const expression = compile ? compile(term.values) : NEVER
    return term.negated ? `!(${expression})` : expression
}

/** The JEXL expression a query is evaluated as; exported for debugging and tests. */
export function toExpression(terms: QueryTerm[]): string {
    return terms.map(compileTerm).join(" && ")
}

const MATCHES_EVERYTHING: RequestMatcher = () => true
const MATCHES_NOTHING: RequestMatcher = () => false

const compiled = new Map<string, RequestMatcher>()

function buildMatcher(query: string): RequestMatcher {
    const terms = parseQuery(query)
    if (terms.length === 0) return MATCHES_EVERYTHING

    const expression = toExpression(terms)
    try {
        const jexlExpression = engine.compile(expression)
        return (request) => jexlExpression.evalSync(request) === true
    } catch (error) {
        // The expression is generated from parsed terms, so this is a bug in the compilers above
        // rather than bad user input. Filter everything out instead of silently ignoring the query.
        console.error(`Could not compile query ${literal(query)} to JEXL expression ${expression}`, error)
        return MATCHES_NOTHING
    }
}

/** Compiles a query into a predicate; compiling is the expensive part, so results are cached. */
export function compileQuery(query: string): RequestMatcher {
    let matcher = compiled.get(query)
    if (!matcher) {
        matcher = buildMatcher(query)
        compiled.set(query, matcher)
    }
    return matcher
}
