import type {RequestUpdate} from "../state.ts";
import {compileQuery, type RequestMatcher} from "./query.ts";
import {
    cachedRange,
    clearRequestCache,
    deleteCachedRequests,
    isPending,
    openRequestCache,
    pendingCachedIds,
    pruneRequestCache,
    readCachedPage,
    writeCachedRequests,
} from "./cache.ts";

/** Requests are loaded a page at a time, from the cache while it reaches and from the server after. */
export const REQUEST_PAGE_SIZE = 200

/** How often the cache is swept for requests that have fallen out of the window it keeps. */
const PRUNE_INTERVAL_MS = 5 * 60 * 1000

/** Cache writes are coalesced over this window, so a burst of live updates is one transaction. */
const CACHE_WRITE_DELAY_MS = 500

/**
 * The server history is not bounded by age, so a query matching nothing must not walk all of it.
 * Paging stops after this many pages in a row that added nothing the query matches, and the list
 * offers to search on. Scrolling an unfiltered list never runs into it: every page adds rows.
 */
const MAX_BARREN_PAGES = 10

/**
 * One row of the list. The request itself is the only reactive part, so a live update re-renders the
 * single row it belongs to instead of the list: the row keeps its identity and its position.
 */
export interface RequestRow {
    readonly id: string
    request: RequestUpdate
    /** Whether the row passes the active query. Not reactive; the visible list is derived from it. */
    matching: boolean
}

function createRow(request: RequestUpdate, matching: boolean): RequestRow {
    let current = $state.raw(request)

    return {
        id: request.request_id,
        get request() {
            return current
        },
        set request(next: RequestUpdate) {
            current = next
        },
        matching,
    }
}

/** What the list needs from the socket. Registered by the socket so this module stays transport free. */
export interface RequestTransport {
    /** Asks for the page of requests started no later than `before`, or for the newest page. */
    history(before: number | null): void

    /** Asks for the current state of requests that may have changed since they were cached. */
    sync(ids: string[]): void
}

let transport: RequestTransport | null = null

export function connectRequestTransport(next: RequestTransport | null) {
    transport = next
}

// Rows by id, and all rows sorted by start time, newest first. Both are plain values: they hold
// every loaded request and proxying them would cost more than it can ever pay back.
const byId = new Map<string, RequestRow>()
let ordered: RequestRow[] = []

let query = ""
let matcher: RequestMatcher = compileQuery("")

let visible = $state.raw<RequestRow[]>([])
let loadedCount = $state(0)
let historyComplete = $state(false)
let loadingPage = $state(false)
let hydrated = $state(false)
// Pages in a row that added nothing matching; reset by a new query and by searching on.
let barrenPages = $state(0)
// Bumped whenever a row is added or removed, so a lookup by id can be a derived value.
let generation = $state(0)

/** `started_at` of the oldest loaded request, i.e. where the next page continues. */
let cursor: number | null = null

/** The range the cache can still serve, or `null` once it is exhausted or was discarded. */
let cacheRange: {oldest: number, newest: number} | null = null

let hydration: Promise<void> | null = null
let pruneTimer: ReturnType<typeof setInterval> | null = null

/** Ids written to the cache once the current burst of updates has settled. */
const cacheWriteQueue = new Map<string, RequestUpdate>()
let cacheWriteTimer: ReturnType<typeof setTimeout> | null = null

export const requestList = {
    /** The rows matching the active query, newest first. */
    get rows() {
        return visible
    },
    /** How many requests are loaded in total, regardless of the query. */
    get loaded() {
        return loadedCount
    },
    /** No older requests are left, so the list stops asking for more. */
    get complete() {
        return historyComplete
    },
    /** A page is on its way; asking for another one would only duplicate it. */
    get loading() {
        return loadingPage
    },
    /** The cache has been read, so an empty list really means "nothing to show". */
    get ready() {
        return hydrated
    },
    /** Paging gave up searching for matches; only an explicit ask continues it. */
    get exhausted() {
        return barrenPages >= MAX_BARREN_PAGES
    },
}

/** The loaded state of a single request, tracked so a detail view follows its live updates. */
export function findRequest(requestId: string): RequestUpdate | undefined {
    generation
    return byId.get(requestId)?.request
}

/** Sets the query the list is filtered by. Re-matching every loaded row is a single pass. */
export function setRequestQuery(next: string) {
    if (next === query) return

    query = next
    matcher = compileQuery(next)
    barrenPages = 0

    for (const row of ordered) row.matching = matcher(row.request)
    visible = ordered.filter((row) => row.matching)
}

function startedAtDescending(a: RequestRow, b: RequestRow): number {
    return b.request.started_at - a.request.started_at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
}

/** Merges two lists that are both sorted newest first, in one pass. */
function mergeDescending(left: RequestRow[], right: RequestRow[]): RequestRow[] {
    const merged: RequestRow[] = new Array(left.length + right.length)

    let index = 0
    let l = 0
    let r = 0
    while (l < left.length && r < right.length) {
        merged[index++] = startedAtDescending(left[l], right[r]) <= 0 ? left[l++] : right[r++]
    }
    while (l < left.length) merged[index++] = left[l++]
    while (r < right.length) merged[index++] = right[r++]

    return merged
}

/**
 * Adds or updates requests. Returns how many of them were not known yet, which is what tells a page
 * of history apart from one that only repeated what is already loaded.
 *
 * [cache] is false for requests that came out of the cache in the first place.
 */
function ingest(updates: RequestUpdate[], {cache = true}: {cache?: boolean} = {}): number {
    const fresh: RequestRow[] = []
    const shown: RequestRow[] = []
    const hidden = new Set<string>()

    for (const update of updates) {
        const known = byId.get(update.request_id)

        if (known) {
            const wasMatching = known.matching
            known.request = update
            known.matching = matcher(update)

            if (known.matching !== wasMatching) {
                if (known.matching) shown.push(known)
                else hidden.add(known.id)
            }
        } else {
            const row = createRow(update, matcher(update))
            byId.set(row.id, row)
            fresh.push(row)
            if (row.matching) shown.push(row)
        }

        if (cache) cacheWriteQueue.set(update.request_id, update)
    }

    if (cache && cacheWriteQueue.size > 0) scheduleCacheWrite()

    if (fresh.length > 0) {
        fresh.sort(startedAtDescending)
        ordered = mergeDescending(ordered, fresh)
        loadedCount = ordered.length
        generation++
    }

    if (shown.length > 0 || hidden.size > 0) {
        shown.sort(startedAtDescending)
        const kept = hidden.size > 0 ? visible.filter((row) => !hidden.has(row.id)) : visible
        visible = shown.length > 0 ? mergeDescending(kept, shown) : kept
    }

    return fresh.length
}

/** Drops requests the server no longer knows, e.g. because they were never persisted. */
function forget(ids: string[]) {
    const gone = new Set(ids.filter((id) => byId.delete(id)))
    if (gone.size === 0) return

    ordered = ordered.filter((row) => !gone.has(row.id))
    visible = visible.filter((row) => !gone.has(row.id))
    loadedCount = ordered.length
    generation++

    void deleteCachedRequests([...gone])
}

function scheduleCacheWrite() {
    if (cacheWriteTimer !== null) return

    cacheWriteTimer = setTimeout(() => {
        cacheWriteTimer = null
        const batch = [...cacheWriteQueue.values()]
        cacheWriteQueue.clear()
        void writeCachedRequests(batch)
    }, CACHE_WRITE_DELAY_MS)
}

/** Records whether a page moved the query forward, which is what bounds the search. */
function countPage(visibleBefore: number) {
    barrenPages = visible.length > visibleBefore ? 0 : barrenPages + 1
}

function oldestLoaded(): number | null {
    return ordered.at(-1)?.request.started_at ?? null
}

/**
 * Opens the cache and shows what it holds. Called once per userspace; the socket waits for it so a
 * page of history is never merged into a list that is still being restored.
 */
export function initRequests(userId: string): Promise<void> {
    pruneTimer ??= setInterval(() => void pruneRequestCache(), PRUNE_INTERVAL_MS)

    hydration ??= (async () => {
        await openRequestCache(userId)
        await pruneRequestCache()

        cacheRange = await cachedRange()
        if (cacheRange) {
            ingest(await readCachedPage(null, REQUEST_PAGE_SIZE), {cache: false})
            cursor = oldestLoaded()
        }

        hydrated = true
    })()

    return hydration
}

/**
 * Empties the cache and starts the list over from the server. Everything the cache holds can be
 * fetched again, so this only ever costs the reload.
 */
export async function clearCachedRequests() {
    if (cacheWriteTimer !== null) {
        clearTimeout(cacheWriteTimer)
        cacheWriteTimer = null
    }
    cacheWriteQueue.clear()

    byId.clear()
    ordered = []
    visible = []
    loadedCount = 0
    generation++

    cursor = null
    cacheRange = null
    historyComplete = false
    barrenPages = 0

    await clearRequestCache()
    await refreshRequests()
}

/** A dropped connection cannot answer the page it was asked for, so the list stops waiting for it. */
export function resetRequestLoading() {
    loadingPage = false
}

export function disposeRequests() {
    if (pruneTimer !== null) clearInterval(pruneTimer)
    pruneTimer = null
}

/**
 * Called whenever the socket is (re)connected: asks for the newest page, so everything that happened
 * while the client was away is caught up on, and revalidates the requests that were still running
 * when they were cached — those are the only ones whose result can have changed since.
 */
export async function refreshRequests() {
    await initRequestsIfNeeded()
    if (!transport) return

    historyComplete = false
    barrenPages = 0
    loadingPage = true
    transport.history(null)

    const pending = new Set(await pendingCachedIds())
    for (const row of ordered) {
        if (isPending(row.request)) pending.add(row.id)
    }
    if (pending.size > 0) transport?.sync([...pending])
}

function initRequestsIfNeeded(): Promise<void> {
    return hydration ?? Promise.resolve()
}

/**
 * Loads the next page of older requests: out of the cache while it still reaches further than the
 * list, from the server after that. Called while scrolling, so only what is looked at is loaded.
 */
export async function loadOlderRequests() {
    if (loadingPage || historyComplete || !hydrated || barrenPages >= MAX_BARREN_PAGES) return

    loadingPage = true
    const visibleBefore = visible.length

    if (cacheRange !== null && cursor !== null && cursor > cacheRange.oldest) {
        // The page is inclusive of the cursor like the server's, so its first entry is usually
        // already loaded. No new request at all means the cache cannot move the cursor any further.
        const page = await readCachedPage(cursor, REQUEST_PAGE_SIZE)
        if (ingest(page, {cache: false}) > 0) {
            cursor = oldestLoaded()
            countPage(visibleBefore)
            loadingPage = false
            return
        }

        cacheRange = null
    }

    if (!transport) {
        loadingPage = false
        return
    }

    transport.history(cursor)
}

/**
 * A page of history from the server. The newest page (`before` is null) is also what a reconnect
 * asks for, so it can turn out not to reach down to what is already loaded — everything below that
 * gap is dropped, because the list may only ever show one uninterrupted range of the history.
 */
export async function onRequestHistory(page: RequestUpdate[], before: number | null, complete: boolean) {
    await initRequestsIfNeeded()

    if (before === null && page.length > 0 && ordered.length > 0) {
        const pageOldest = page.reduce((oldest, request) => Math.min(oldest, request.started_at), Infinity)

        // Every loaded request is older than the whole page, so nothing connects the two and the
        // list would show a hole. What is loaded — and the cache it came from — is dropped instead.
        if (pageOldest > ordered[0].request.started_at) {
            byId.clear()
            ordered = []
            visible = []
            loadedCount = 0
            generation++
            cacheRange = null
            cacheWriteQueue.clear()
            void clearRequestCache()
        }
    }

    const previousCursor = cursor
    const visibleBefore = visible.length
    const added = ingest(page)
    cursor = oldestLoaded()

    // Only a page asked for with a cursor is a step of the search; the newest page repeats what is
    // already loaded by design and a reconnect asks for it again.
    if (before !== null) countPage(visibleBefore)

    // A short page means the server is out of history; a page that was asked for with a cursor and
    // cannot move it any further ends the paging just as well. The newest page is exempt: it repeats
    // what is already loaded by design, and a reconnect asks for it again.
    historyComplete = complete || (before !== null && added === 0 && cursor === previousCursor)
    loadingPage = false
}

/** The current state of requests that were cached while they were still running. */
export async function onRequestSync(requests: RequestUpdate[], missing: string[]) {
    await initRequestsIfNeeded()

    ingest(requests)
    forget(missing)
}

/** Continues a search that gave up on its own, for another [MAX_BARREN_PAGES] pages. */
export function searchFurtherBack() {
    barrenPages = 0
    void loadOlderRequests()
}

/** A live update from the tunnel. */
export function onRequestUpdates(updates: RequestUpdate[]) {
    ingest(updates)
}
