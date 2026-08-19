import {ArrowsLeftRightIcon} from "phosphor-svelte";
import {methodColors} from "$lib/components/requests/colors";
import {FILTER_METHODS} from "./filter.ts";
import {QUALIFIER_NAMES} from "./query.ts";

export type QualifierIcon = typeof ArrowsLeftRightIcon

export interface QualifierValue {
    /** The value as it is written in the query. */
    query: string,
    /** i18n key of the label shown next to the value. */
    label?: string,
    /** Extra classes for the value, e.g. the color of a request method. */
    class?: string
}

export interface QueryQualifier {
    /** The text used in the query, in front of the colon. */
    query: string,
    /** i18n key of the label shown next to the query text. */
    label?: string,
    icon?: QualifierIcon,
    /** Values offered as completions; qualifiers with free form values have none. */
    values?: QualifierValue[],
    /** Values that are loaded instead of being known up front. */
    source?: "projects"
}

const METHOD_VALUES: QualifierValue[] = FILTER_METHODS.map((method) => ({
    query: method,
    class: methodColors[method as keyof typeof methodColors],
}))

const KIND_VALUES: QualifierValue[] = [
    {query: "http", label: "userspace.requests.filter.values.http"},
    {query: "websocket", label: "userspace.requests.filter.values.websocket"},
]

// How a qualifier is presented; the query language decides which ones exist at all.
const DISPLAY: Record<string, Omit<QueryQualifier, "query">> = {
    method: {
        label: "userspace.requests.filter.qualifiers.method",
        icon: ArrowsLeftRightIcon,
        values: METHOD_VALUES,
    },
    is: {
        label: "userspace.requests.filter.qualifiers.is",
        values: KIND_VALUES,
    },
    status: {label: "userspace.requests.filter.qualifiers.status"},
    project: {
        label: "userspace.requests.filter.qualifiers.project",
        source: "projects",
    },
    service: {label: "userspace.requests.filter.qualifiers.service"},
}

export const QUERY_QUALIFIERS: QueryQualifier[] = QUALIFIER_NAMES.map((query) => ({
    query,
    ...DISPLAY[query],
}))

export function findQualifier(query: string): QueryQualifier | undefined {
    return QUERY_QUALIFIERS.find((qualifier) => qualifier.query === query)
}
