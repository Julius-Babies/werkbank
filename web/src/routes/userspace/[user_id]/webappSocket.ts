import {latestRequests, MAX_LATEST_REQUESTS, tunnelState, user, type RequestUpdate} from "./state.ts";
import {page} from "$app/state";
import {requests} from "./requests/requests.ts";

let webSocket: WebSocket | null = null;

// Incoming request.update messages arrive in bursts (a single page opened through
// the tunnel produces many sub-requests, each emitting several status updates).
// Applying every message to the stores individually caused a full array copy and a
// full table re-render per message. We coalesce a burst into a single store write
// per animation frame instead, and merge by id in O(1) via an insertion-ordered Map.
let pendingUpdates = new Map<string, RequestUpdate>();
let flushHandle: number | null = null;

function scheduleFlush() {
    if (flushHandle !== null) return;
    flushHandle = requestAnimationFrame(flushPendingUpdates);
}

function applyUpdates(list: RequestUpdate[], buffered: Map<string, RequestUpdate>, cap?: number): RequestUpdate[] {
    const indexById = new Map<string, number>();
    for (let i = 0; i < list.length; i++) {
        indexById.set(list[i].request_id, i);
    }

    let next: RequestUpdate[] | null = null; // copy lazily; leave the array untouched if nothing changes
    const fresh: RequestUpdate[] = [];
    for (const update of buffered.values()) {
        const idx = indexById.get(update.request_id);
        if (idx !== undefined) {
            if (!next) next = list.slice();
            next[idx] = update;
        } else {
            fresh.push(update);
        }
    }

    if (!next) {
        if (fresh.length === 0) return list;
        next = list.slice();
    }

    if (fresh.length > 0) {
        // Match the previous per-message behaviour: newest (last received) ends up on top.
        fresh.reverse();
        next = [...fresh, ...next];
    }

    if (cap !== undefined && next.length > cap) {
        next = next.slice(0, cap);
    }

    return next;
}

function flushPendingUpdates() {
    flushHandle = null;
    if (pendingUpdates.size === 0) return;

    const buffered = pendingUpdates;
    pendingUpdates = new Map();

    latestRequests.update(list => applyUpdates(list, buffered, MAX_LATEST_REQUESTS));

    if (page.route?.id?.startsWith("/userspace/[user_id]/requests")) {
        requests.update(list => applyUpdates(list, buffered));
    }
}

export default function () {
    return user.subscribe(user => {
        if (!user) {
            webSocket?.close();
            return;
        }

        function connect() {
            webSocket = new WebSocket("/api/webapp/ws")

            webSocket.onmessage = (event) => {
                const message = JSON.parse(event.data);

                if (message.type === "tunnel.inactive") {
                    tunnelState.set({ active: false, pingMs: null });
                } else if (message.type === "tunnel.active") {
                    tunnelState.set({ active: true, pingMs: message.ping_ms ?? null });
                } else if (message.type === "request.update") {
                    // Buffer by id (insertion-ordered) and flush once per frame. Repeated
                    // updates for the same request within a frame collapse to the latest
                    // value while keeping the first-seen position (matches prior behaviour).
                    pendingUpdates.set(message.request_id, message);
                    scheduleFlush();
                }
            }

            webSocket.onclose = (event) => {
                if (!event.wasClean) {
                    tunnelState.set(null);
                    console.log("WebSocket connection closed unexpectedly");
                    setTimeout(() => connect(), 5000);
                }
            }
        }

        connect();
    })
}
