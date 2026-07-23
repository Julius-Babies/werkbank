import {latestRequests, MAX_LATEST_REQUESTS, tunnelState, user, watchedFrames} from "./state.ts";
import {page} from "$app/state";
import {requests} from "./requests/requests.ts";

let webSocket: WebSocket | null = null;

// The request whose WebSocket frame timeline the detail page is currently watching, if any.
let currentWatchId: string | null = null;

/** Subscribe to live WebSocket frames of a request. Clears any previously watched frames. */
export function watchFrames(requestId: string) {
    if (currentWatchId === requestId) return;
    if (currentWatchId) unwatchFrames();
    currentWatchId = requestId;
    watchedFrames.set([]);
    if (webSocket?.readyState === WebSocket.OPEN) {
        webSocket.send(JSON.stringify({type: "watch", request_id: requestId}));
    }
}

/** Stop watching frames of the currently watched request. */
export function unwatchFrames() {
    if (!currentWatchId) return;
    if (webSocket?.readyState === WebSocket.OPEN) {
        webSocket.send(JSON.stringify({type: "unwatch", request_id: currentWatchId}));
    }
    currentWatchId = null;
    watchedFrames.set([]);
}

export default function () {
    return user.subscribe(user => {
        if (!user) {
            webSocket?.close();
            return;
        }

        function connect() {
            webSocket = new WebSocket("/api/webapp/ws")

            webSocket.onopen = () => {
                // Re-arm an active watch after a (re)connect.
                if (currentWatchId) {
                    webSocket?.send(JSON.stringify({type: "watch", request_id: currentWatchId}));
                }
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
                } else if (message.type === "request.update") {
                    latestRequests.update(list => {
                        const idx = list.findIndex(r => r.request_id === message.request_id);
                        let next;
                        if (idx !== -1) {
                            next = list.toSpliced(idx, 1, message);
                        } else {
                            next = [message, ...list];
                        }
                        if (next.length > MAX_LATEST_REQUESTS) {
                            next = next.slice(0, MAX_LATEST_REQUESTS);
                        }
                        return next;
                    });

                    if (page.route?.id?.startsWith("/userspace/[user_id]/requests")) {
                        requests.update(list => {
                            const idx = list.findIndex(r => r.request_id === message.request_id);
                            let next;
                            if (idx !== -1) {
                                next = list.toSpliced(idx, 1, message);
                            } else {
                                next = [message, ...list];
                            }
                            return next;
                        })
                    }
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
