import {writable} from "svelte/store";

export const MAX_LATEST_REQUESTS = 5;

export interface RequestTarget {
    project_id: string;
    project_name: string;
    service_name: string | null;
}

export type RequestKind = "http" | "websocket";

export interface RequestUpdate {
    request_id: string;
    kind: RequestKind;
    method: string;
    uri: string;
    target: RequestTarget | null;
    status_code: number | null;
    error: string | null;
    started_at: number;
    sent_to_tunnel_at: number | null;
    response_started_at: number | null;
    completed_at: number | null;
    ws_frames_sent: number;
    ws_frames_received: number;
}

export type WsFrameDirection = "client_to_server" | "server_to_client";
export type WsFrameOpcode = "text" | "binary" | "close";

export interface WsFrame {
    request_id: string;
    sequence: number;
    direction: WsFrameDirection;
    opcode: WsFrameOpcode;
    text: string | null;
    binary_base64: string | null;
    size: number;
    timestamp: number;
    close_code: number | null;
    close_reason: string | null;
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

// Live WebSocket frames for the request currently watched on the detail page (see webappSocket.ts).
export let watchedFrames = writable<WsFrame[]>([]);

export let title = writable<string>("");
