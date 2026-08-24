import type {RequestUpdate} from "../state.ts";

/**
 * Client side cache of the request list, backed by IndexedDB.
 *
 * The server hands out the whole history, the cache keeps only the most recent hours of it: paging
 * further back is a socket round trip, and holding a browser database of every request a user ever
 * made to save that round trip is not a trade worth making. On every open, and periodically
 * afterwards, everything older is deleted. What remains is exactly one contiguous range ending at
 * the newest request the client has ever seen — see `requests.svelte.ts`, which discards the cache
 * when a session cannot connect its own pages to it.
 *
 * A request that was still running when it was cached can still change. Those rows are written with a
 * `pending` marker, which doubles as an index: IndexedDB only indexes records that actually have the
 * property, so the marked ids can be read back without scanning the store. They are revalidated
 * against the server on every start.
 */

const DB_VERSION = 1
const STORE = "requests"
const STARTED_AT_INDEX = "started_at"
const PENDING_INDEX = "pending"

/** How much of the history is worth keeping in the browser; older requests are paged in on demand. */
export const REQUEST_CACHE_WINDOW_MS = 8 * 60 * 60 * 1000

/** A cached request; `pending` marks a request whose result can still change. */
type CachedRequest = RequestUpdate & {pending?: 1}

/** True while the request can still change, i.e. while it has neither finished nor failed. */
export function isPending(request: RequestUpdate): boolean {
    return request.completed_at === null && request.error === null
}

let database: Promise<IDBDatabase | null> | null = null

function promisify<T>(request: IDBRequest<T>): Promise<T> {
    return new Promise((resolve, reject) => {
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
    })
}

/**
 * Opens the cache of the given userspace. The database is named after it because the same browser
 * profile can reach several userspaces, and a request list must never leak between them.
 *
 * Everything here degrades to "no cache" instead of throwing: IndexedDB is unavailable in private
 * windows of some browsers and can fail to open for reasons the app cannot do anything about.
 */
export function openRequestCache(userId: string): Promise<IDBDatabase | null> {
    database ??= new Promise<IDBDatabase | null>((resolve) => {
        if (typeof indexedDB === "undefined") return resolve(null)

        const request = indexedDB.open(`werkbank-requests-${userId}`, DB_VERSION)

        request.onupgradeneeded = () => {
            const store = request.result.objectStoreNames.contains(STORE)
                ? request.transaction!.objectStore(STORE)
                : request.result.createObjectStore(STORE, {keyPath: "request_id"})

            if (!store.indexNames.contains(STARTED_AT_INDEX)) store.createIndex(STARTED_AT_INDEX, "started_at")
            if (!store.indexNames.contains(PENDING_INDEX)) store.createIndex(PENDING_INDEX, "pending")
        }

        request.onsuccess = () => resolve(request.result)
        request.onerror = () => {
            console.warn("Could not open the request cache, continuing without it", request.error)
            resolve(null)
        }
    })

    return database
}

async function transaction<T>(
    mode: IDBTransactionMode,
    run: (store: IDBObjectStore) => Promise<T>,
): Promise<T | null> {
    const db = await database
    if (!db) return null

    try {
        return await run(db.transaction(STORE, mode).objectStore(STORE))
    } catch (error) {
        console.warn("Request cache access failed, continuing without it", error)
        return null
    }
}

/**
 * The page of cached requests that started no later than [before], newest first. Compared
 * inclusively like the server does, because several requests can share a millisecond; callers
 * deduplicate by id.
 */
export async function readCachedPage(before: number | null, limit: number): Promise<RequestUpdate[]> {
    return await transaction("readonly", (store) => {
        const range = before === null ? null : IDBKeyRange.upperBound(before)
        const cursor = store.index(STARTED_AT_INDEX).openCursor(range, "prev")

        return new Promise<RequestUpdate[]>((resolve, reject) => {
            const page: RequestUpdate[] = []

            cursor.onsuccess = () => {
                const position = cursor.result
                if (!position || page.length >= limit) return resolve(page)

                const {pending: _pending, ...request} = position.value as CachedRequest
                page.push(request)
                position.continue()
            }
            cursor.onerror = () => reject(cursor.error)
        })
    }) ?? []
}

/** How far the cached range reaches, as the `started_at` of its oldest and newest request. */
export async function cachedRange(): Promise<{oldest: number, newest: number} | null> {
    return await transaction("readonly", async (store) => {
        const index = store.index(STARTED_AT_INDEX)
        const oldest = await promisify(index.openCursor(null, "next"))
        const newest = await promisify(index.openCursor(null, "prev"))
        if (!oldest || !newest) return null

        return {oldest: oldest.key as number, newest: newest.key as number}
    })
}

/** The ids of cached requests whose result can still have changed since they were written. */
export async function pendingCachedIds(): Promise<string[]> {
    return await transaction("readonly", (store) =>
        promisify(store.index(PENDING_INDEX).getAllKeys() as IDBRequest<IDBValidKey[]>),
    ).then((keys) => (keys ?? []).map(String))
}

export async function writeCachedRequests(requests: RequestUpdate[], now: number = Date.now()): Promise<void> {
    // Scrolling far enough back walks past the window; writing those rows only to delete them at the
    // next sweep is pure churn, so they stay where they came from.
    const fresh = requests.filter((request) => request.started_at >= now - REQUEST_CACHE_WINDOW_MS)
    if (fresh.length === 0) return

    await transaction("readwrite", async (store) => {
        for (const request of fresh) {
            const cached: CachedRequest = {...request}
            // Only pending requests carry the marker, so the index holds exactly those ids.
            if (isPending(request)) cached.pending = 1
            store.put(cached)
        }
    })
}

export async function deleteCachedRequests(ids: string[]): Promise<void> {
    if (ids.length === 0) return

    await transaction("readwrite", async (store) => {
        for (const id of ids) store.delete(id)
    })
}

/** How many requests the cache currently holds. */
export async function countCachedRequests(): Promise<number> {
    return await transaction("readonly", (store) => promisify(store.count())) ?? 0
}

export async function clearRequestCache(): Promise<void> {
    await transaction("readwrite", async (store) => {
        store.clear()
    })
}

/** Drops everything that has fallen out of the window the browser keeps. */
export async function pruneRequestCache(now: number = Date.now()): Promise<void> {
    await transaction("readwrite", (store) => {
        const cutoff = IDBKeyRange.upperBound(now - REQUEST_CACHE_WINDOW_MS, true)
        const cursor = store.index(STARTED_AT_INDEX).openCursor(cutoff)

        return new Promise<void>((resolve, reject) => {
            cursor.onsuccess = () => {
                const position = cursor.result
                if (!position) return resolve()

                position.delete()
                position.continue()
            }
            cursor.onerror = () => reject(cursor.error)
        })
    })
}
