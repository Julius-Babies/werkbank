import {latestRequests, MAX_LATEST_REQUESTS, tunnelState, user, watchedFrames, type RequestUpdate} from "./state.ts";
import {page} from "$app/state";
import {
    connectRequestTransport,
    disposeRequests,
    initRequests,
    onRequestHistory,
    onRequestSync,
    onRequestUpdates,
    refreshRequests,
    resetRequestLoading,
    REQUEST_PAGE_SIZE,
} from "./requests/requests.svelte.ts";

let webSocket: WebSocket | null = null;

// The request whose WebSocket frame timeline the detail page is currently watching, if any.
let currentWatchId: string | null = null;

function send(message: Record<string, unknown>) {
    if (webSocket?.readyState !== WebSocket.OPEN) return;
    webSocket.send(JSON.stringify(message));
}

/** Subscribe to live WebSocket frames of a request. Clears any previously watched frames. */
export function watchFrames(requestId: string) {
    if (currentWatchId === requestId) return;
    if (currentWatchId) unwatchFrames();
    currentWatchId = requestId;
    watchedFrames.set([]);
    send({type: "watch", request_id: requestId});
}

/** Stop watching frames of the currently watched request. */
export function unwatchFrames() {
    if (!currentWatchId) return;
    send({type: "unwatch", request_id: currentWatchId});
    currentWatchId = null;
    watchedFrames.set([]);
}

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

function applyUpdates(list: RequestUpdate[], buffered: Map<string, RequestUpdate>, cap: number): RequestUpdate[] {
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

    if (next.length > cap) {
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

    // The list is fed regardless of the current route: it is cached across reloads anyway, so
    // dropping updates while another page is open would only make it wrong until the next reload.
    onRequestUpdates([...buffered.values()]);
}

export default function () {
    const userId = page.params.user_id;
    if (userId) void initRequests(userId);

    connectRequestTransport({
        history: (before) => send({type: "history", before, limit: REQUEST_PAGE_SIZE}),
        sync: (ids) => send({type: "sync", request_ids: ids}),
    });

    const unsubscribe = user.subscribe(user => {
        if (!user) {
            webSocket?.close();
            return;
        }

        function connect() {
            webSocket = new WebSocket("/api/webapp/ws")

            webSocket.onopen = () => {
                // Re-arm an active watch after a (re)connect.
                if (currentWatchId) {
                    send({type: "watch", request_id: currentWatchId});
                }

                // Catch up on everything that happened while there was no connection, and revalidate
                // the requests that were still running when they were cached.
                void refreshRequests();
            }

            webSocket.onmessage = (event) => {
                const message = JSON.parse(event.data);

                if (message.type === "tunnel.inactive") {
                    tunnelState.set({ active: false, pingMs: null });
                } else if (message.type === "tunnel.active") {
                    tunnelState.set({ active: true, pingMs: message.ping_ms ?? null });
                } else if (message.type === "ws.frame") {
                    if (message.request_id === currentWatchId) {
                        watchedFrames.update(list => {
                            if (list.some(f => f.sequence === message.sequence)) return list;
                            return [...list, message];
                        });
                    }
                } else if (message.type === "request.history") {
                    void onRequestHistory(message.requests, message.before ?? null, message.complete);
                } else if (message.type === "request.sync") {
                    void onRequestSync(message.requests, message.missing);
                } else if (message.type === "request.update") {
                    // Buffer by id (insertion-ordered) and flush once per frame. Repeated
                    // updates for the same request within a frame collapse to the latest
                    // value while keeping the first-seen position (matches prior behaviour).
                    pendingUpdates.set(message.request_id, message);
                    scheduleFlush();
                }
            }

            webSocket.onclose = (event) => {
                resetRequestLoading();

                if (!event.wasClean) {
                    tunnelState.set(null);
                    console.log("WebSocket connection closed unexpectedly");
                    setTimeout(() => connect(), 5000);
                }
            }
        }

        connect();
    })

    return () => {
        unsubscribe();
        connectRequestTransport(null);
        disposeRequests();
    };
}
