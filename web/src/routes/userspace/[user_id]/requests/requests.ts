import {writable} from "svelte/store";
import type {RequestUpdate} from "../state.ts";

export const requests = writable<RequestUpdate[]>([])

export async function fetchRequests() {
    const response = await fetch("/api/webapp/requests")
    const data = await response.json()
    requests.set(data)
}