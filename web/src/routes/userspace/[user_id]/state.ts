import {writable} from "svelte/store";

export const MAX_LATEST_REQUESTS = 5;

export interface RequestTarget {
    project_id: string;
    project_name: string;
    service_name: string | null;
}

export interface RequestUpdate {
    request_id: string;
    method: string;
    uri: string;
    target: RequestTarget | null;
    status_code: number | null;
    error: string | null;
    started_at: number;
    sent_to_tunnel_at: number | null;
    response_started_at: number | null;
    completed_at: number | null;
}

export let user = writable<{
    username: string,
    email: string,
    avatar_url: string,
} | null>(null);

export let tunnelState = writable<{
    active: boolean,
    pingMs: number | null,
} | null>(null);

export let latestRequests = writable<RequestUpdate[]>([]);

export let title = writable<string>("");
