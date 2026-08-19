import {writable} from "svelte/store";
import type {RequestUpdate} from "../state.ts";

/** Requests are loaded a page at a time: the newest page over HTTP, older ones over the socket. */
export const REQUEST_PAGE_SIZE = 200

export const requests = writable<RequestUpdate[]>([])

/** No older requests are left on the server, so the list stops asking for more. */
export const historyComplete = writable(false)

export async function fetchRequests() {
    const response = await fetch(`/api/webapp/requests?limit=${REQUEST_PAGE_SIZE}`)
    const data = await response.json() as RequestUpdate[]

    if (data.length < REQUEST_PAGE_SIZE) historyComplete.set(true)

    // Merge instead of overwrite: still-open WebSocket connections live only in the in-memory
    // tunnel state and are persisted to the DB only on close, so they arrive via the live socket
    // and are absent from this fetch. A plain `set` would wipe them until their next frame; keep
    // any live-only entries (by id) on top of the freshly fetched history.
    requests.update(current => {
        const fetchedIds = new Set(data.map(r => r.request_id))
        const liveOnly = current.filter(r => !fetchedIds.has(r.request_id))
        return [...liveOnly, ...data]
    })
}

/** Appends an older page; returns how many of its requests were not known yet. */
export function appendOlderRequests(page: RequestUpdate[]): number {
    let added = 0

    requests.update(list => {
        const known = new Set(list.map(request => request.request_id))
        const older = page.filter(request => !known.has(request.request_id))
        added = older.length

        // The page is older than everything loaded, so appending keeps the list sorted by age.
        return added === 0 ? list : [...list, ...older]
    })

    return added
}
