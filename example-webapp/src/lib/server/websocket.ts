import { WebSocketServer, type WebSocket } from 'ws';
import type { Server } from 'node:http';
import type { Http2SecureServer } from 'node:http2';
import type { Duplex } from 'node:stream';

type UpgradableServer = Server | Http2SecureServer;

export const WS_PATH = '/ws';

/**
 * Attach a WebSocket server to an existing HTTP server.
 *
 * The endpoint lives at {@link WS_PATH}. This is reachable through the werkbank
 * proxy (via the configured base URL), so it can be used to debug how the proxy
 * forwards WebSocket upgrades and messages.
 *
 * Behaviour:
 * - greets each client on connect,
 * - periodically pushes a server-side "tick" message,
 * - echoes any message it receives back to the sender.
 */
export function attachWebSocketServer(server: UpgradableServer) {
	// Guard against double-attaching when Vite reloads the config.
	if ((server as { __wsAttached?: boolean }).__wsAttached) return;
	(server as { __wsAttached?: boolean }).__wsAttached = true;

	const wss = new WebSocketServer({ noServer: true });

	server.on('upgrade', (request, socket: Duplex, head) => {
		const { url } = request;
		if (!url) return;
		const pathname = new URL(url, 'http://localhost').pathname;

		// Leave Vite's own HMR socket (and anything else) untouched.
		if (pathname !== WS_PATH) return;

		wss.handleUpgrade(request, socket, head, (ws) => {
			wss.emit('connection', ws, request);
		});
	});

	wss.on('connection', (ws: WebSocket) => {
		send(ws, 'welcome', 'Connected to werkbank sample WebSocket server');

		// Periodic server-initiated messages ("ab und an").
		const interval = setInterval(() => {
			if (ws.readyState === ws.OPEN) {
				send(ws, 'tick', `Server tick at ${new Date().toISOString()}`);
			}
		}, 5000);

		ws.on('message', (data) => {
			send(ws, 'echo', `Echo: ${data.toString()}`);
		});

		ws.on('close', () => clearInterval(interval));
		ws.on('error', () => clearInterval(interval));
	});
}

function send(ws: WebSocket, type: string, message: string) {
	ws.send(JSON.stringify({ type, message, timestamp: new Date().toISOString() }));
}
