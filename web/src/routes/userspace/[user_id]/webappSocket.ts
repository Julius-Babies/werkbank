import {tunnelState, user} from "./state.ts";

let webSocket: WebSocket | null = null;

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