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
 *   !method:GET         negation, also in front of a group
 *   a b, a AND b        both have to match; whitespace is an implicit AND
 *   a OR b              either has to match; OR binds weaker than AND
 *   (a OR b) c          parentheses group terms
 *
 * `AND` and `OR` are only operators in upper case, so `or` stays usable as free text. A query is
 * compiled into a JEXL expression and evaluated once per request, with the request itself as the
 * evaluation context.
 */

export interface QueryTerm {
    /** Qualifier name, or `null` for a free text term. */
    qualifier: string | null,
    /** Values of the term; the term matches a request if any of them matches. */
    values: string[]
}

export type QueryNode =
    | {type: "term", term: QueryTerm}
    | {type: "and", nodes: QueryNode[]}
    | {type: "or", nodes: QueryNode[]}
    | {type: "not", node: QueryNode}

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
    project: (values) => membership("target.project_key", values),
    service: (values) => membership("target.service_name", values),
}

/** The qualifiers the query language understands. */
export const QUALIFIER_NAMES = Object.keys(QUALIFIERS)

/** Free text is a case insensitive substring match on the request URI. */
function freeText(values: string[]): string {
    return values
        .map((value) => `${literal(value.toLowerCase())} in uri|lower`)
        .join(" || ")
}

const OPERATORS = ["AND", "OR"]

export interface QueryToken {
    /** The token as written, so the tokens concatenate back into the query. */
    text: string,
    kind: "whitespace" | "paren" | "operator" | "term"
}

/**
 * Splits a query into tokens. Used by the parser and by the highlighting, so both agree on where
 * a term starts and ends.
 */
export function lex(query: string): QueryToken[] {
    const tokens: QueryToken[] = []
    let current = ""
    let quoted = false

    function flush() {
        if (!current) return
        tokens.push({text: current, kind: OPERATORS.includes(current) ? "operator" : "term"})
        current = ""
    }

    for (const char of query) {
        if (char === "\"") {
            quoted = !quoted
            current += char
        } else if (quoted) {
            current += char
        } else if (/\s/.test(char)) {
            flush()
            const previous = tokens.at(-1)
            if (previous?.kind === "whitespace") previous.text += char
            else tokens.push({text: char, kind: "whitespace"})
        } else if (char === "(" || char === ")") {
            flush()
            tokens.push({text: char, kind: "paren"})
        } else {
            current += char
        }
    }

    flush()
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

/** A term token, without its negation. `null` when it carries no values, e.g. a bare `method:`. */
function parseTerm(token: string): QueryTerm | null {
    const colon = indexOfUnquoted(token, ":")

    // A leading colon or none at all makes the whole token free text, which is never split on
    // commas: a comma is a plain character in a search phrase.
    if (colon <= 0) {
        const text = unquote(token)
        return text ? {qualifier: null, values: [text]} : null
    }

    const values = splitValues(token.slice(colon + 1))
    return values.length === 0 ? null : {qualifier: token.slice(0, colon).toLowerCase(), values}
}

function combine(type: "and" | "or", nodes: QueryNode[]): QueryNode | null {
    if (nodes.length === 0) return null
    if (nodes.length === 1) return nodes[0]
    return {type, nodes}
}

/**
 * Parses a query into its tree. Incomplete input is tolerated rather than rejected — queries are
 * parsed while they are being typed, so a missing closing parenthesis or a trailing operator just
 * parses to what is there.
 */
export function parseQuery(query: string): QueryNode | null {
    const tokens = lex(query).filter((token) => token.kind !== "whitespace")
    let index = 0

    function parseOr(): QueryNode | null {
        const nodes: QueryNode[] = []

        for (const node of [parseAnd()]) if (node) nodes.push(node)
        while (tokens[index]?.kind === "operator" && tokens[index].text === "OR") {
            index++
            const node = parseAnd()
            if (node) nodes.push(node)
        }

        return combine("or", nodes)
    }

    function parseAnd(): QueryNode | null {
        const nodes: QueryNode[] = []

        while (index < tokens.length) {
            const token = tokens[index]
            if (token.text === "OR" && token.kind === "operator") break
            if (token.text === ")" && token.kind === "paren") break

            // An explicit AND is only a separator, juxtaposition means the same thing.
            if (token.kind === "operator") {
                index++
                continue
            }

            const node = parseUnary()
            if (node) nodes.push(node)
        }

        return combine("and", nodes)
    }

    function parseUnary(): QueryNode | null {
        const token = tokens[index]

        if (token.kind === "paren") {
            index++
            const node = parseOr()
            if (tokens[index]?.text === ")") index++
            return node
        }

        index++

        if (token.text === "!") {
            const node = index < tokens.length ? parseUnary() : null
            return node ? {type: "not", node} : null
        }

        const negated = token.text.startsWith("!")
        const term = parseTerm(negated ? token.text.slice(1) : token.text)
        if (!term) return null

        return negated ? {type: "not", node: {type: "term", term}} : {type: "term", term}
    }

    return parseOr()
}

function quoteValue(value: string): string {
    return /[\s,:"()]/.test(value) ? `"${unquote(value)}"` : value
}

function serializeTerm(term: QueryTerm): string {
    const values = term.values.map(quoteValue).join(",")
    return term.qualifier === null ? values : `${term.qualifier}:${values}`
}

/** Inverse of `parseQuery`; the result parses back into the tree it was built from. */
export function serializeQuery(node: QueryNode | null): string {
    if (node === null) return ""

    switch (node.type) {
        case "term":
            return serializeTerm(node.term)
        case "not":
            return `!${group(node.node, node.node.type === "and" || node.node.type === "or")}`
        case "and":
            // AND binds tighter than OR, so only an OR child needs parentheses.
            return node.nodes.map((child) => group(child, child.type === "or")).join(" ")
        case "or":
            return node.nodes.map((child) => serializeQuery(child)).join(" OR ")
    }
}

function group(node: QueryNode, parenthesized: boolean): string {
    const query = serializeQuery(node)
    return parenthesized ? `(${query})` : query
}

/** Matches the term the caret sits in, capturing its qualifier and the values typed so far. */
const TERM_VALUES = /(?:^|[\s(])!?([^\s:"()]+):([^\s()]*)$/

export interface ValueContext {
    qualifier: string,
    /** The values of the term that are already typed, so they can be completed. */
    values: string
}

/** The term the caret sits in, or `null` when the caret is not behind a `qualifier:`. */
export function valuesAtCaret(query: string, caret: number): ValueContext | null {
    const match = TERM_VALUES.exec(query.slice(0, caret))
    return match ? {qualifier: match[1].toLowerCase(), values: match[2]} : null
}

/** Matches a qualifier being typed at the caret: a term that has no colon yet. */
const QUALIFIER_PREFIX = /(?:^|[\s(])!?([^\s:"()]*)$/

/**
 * The part of a qualifier typed in front of the caret, without a leading `!`, so it can be
 * completed. `null` when the caret is not in a qualifier, e.g. because the term already has a
 * colon or is quoted.
 */
export function qualifierPrefixAtCaret(query: string, caret: number): string | null {
    return QUALIFIER_PREFIX.exec(query.slice(0, caret))?.[1] ?? null
}

/** A piece of a query, used to highlight it while it is being edited. */
export interface QuerySegment {
    text: string,
    role: "qualifier" | "value" | "text" | "operator",
    /** Qualifier of the term a value belongs to, so the value can be colored by its meaning. */
    qualifier?: string,
    /** The value without its quotes. */
    value?: string
}

/**
 * Splits a query into segments for syntax highlighting. Unlike `parseQuery` this keeps every
 * character, including whitespace and half written terms, so the segments concatenate back into
 * the exact input and can be laid over the query input.
 */
export function highlightQuery(query: string): QuerySegment[] {
    const segments: QuerySegment[] = []

    for (const token of lex(query)) {
        if (token.kind === "whitespace") {
            segments.push({text: token.text, role: "text"})
            continue
        }

        if (token.kind === "operator" || token.kind === "paren" || token.text === "!") {
            segments.push({text: token.text, role: "operator"})
            continue
        }

        const body = token.text.startsWith("!") ? token.text.slice(1) : token.text
        const colon = indexOfUnquoted(body, ":")
        if (colon <= 0) {
            segments.push({text: token.text, role: "text"})
            continue
        }

        if (body !== token.text) segments.push({text: "!", role: "operator"})
        segments.push({text: body.slice(0, colon + 1), role: "qualifier"})

        // Keep the commas as their own segments so the values line up with the original text.
        for (const [index, value] of body.slice(colon + 1).split(",").entries()) {
            if (index > 0) segments.push({text: ",", role: "text"})
            if (!value) continue

            const qualifier = body.slice(0, colon).toLowerCase()
            segments.push({text: value, role: "value", qualifier, value: unquote(value)})
        }
    }

    return segments
}

function compileTerm(term: QueryTerm): string {
    const compile = term.qualifier === null ? freeText : QUALIFIERS[term.qualifier]
    return compile ? compile(term.values) : NEVER
}

/** The JEXL expression a query is evaluated as; exported for debugging and tests. */
export function toExpression(node: QueryNode | null): string {
    if (node === null) return ""

    switch (node.type) {
        case "term":
            return compileTerm(node.term)
        case "not":
            return `!(${toExpression(node.node)})`
        case "and":
            return `(${node.nodes.map(toExpression).join(" && ")})`
        case "or":
            return `(${node.nodes.map(toExpression).join(" || ")})`
    }
}

const MATCHES_EVERYTHING: RequestMatcher = () => true
const MATCHES_NOTHING: RequestMatcher = () => false

const compiled = new Map<string, RequestMatcher>()

function buildMatcher(query: string): RequestMatcher {
    const expression = toExpression(parseQuery(query))
    if (!expression) return MATCHES_EVERYTHING

    try {
        const jexlExpression = engine.compile(expression)
        return (request) => jexlExpression.evalSync(request) === true
    } catch (error) {
        // The expression is generated from a parsed query, so this is a bug in the compilers above
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
