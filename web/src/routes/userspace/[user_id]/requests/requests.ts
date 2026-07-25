import {writable} from "svelte/store";
import type {RequestUpdate} from "../state.ts";

export const requests = writable<RequestUpdate[]>([])

export async function fetchRequests() {
    const response = await fetch("/api/webapp/requests")
    const data = await response.json() as RequestUpdate[]
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