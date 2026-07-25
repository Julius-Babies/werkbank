import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-auto';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig, type PluginOption } from 'vite';
import { attachWebSocketServer } from './src/lib/server/websocket';

/** Attaches the WebSocket server to Vite's HTTP server in dev and preview. */
const websocketPlugin: PluginOption = {
	name: 'werkbank-websocket',
	configureServer(server) {
		if (server.httpServer) attachWebSocketServer(server.httpServer);
	},
	configurePreviewServer(server) {
		if (server.httpServer) attachWebSocketServer(server.httpServer);
	}
};

export default defineConfig({
	server: {
		// The werkbank CLI connects to the local service over IPv4 (127.0.0.1:5173,
		// see TunnelRequestResolver). Vite's default host `localhost` may bind to IPv6
		// (::1) only, which makes that IPv4 connect fail with "connection refused".
		// Bind explicitly so the proxy can always reach the dev server.
		host: '127.0.0.1',
		port: 5173,
		strictPort: true,
		hmr: {
			// The app is served through the werkbank proxy, so the browser is on the
			// proxy's https domain, not 127.0.0.1:5173. Vite would otherwise tell the
			// HMR client to connect to `:5173` (from server.port), which the proxy does
			// not listen on. Leaving `host` unset makes the client fall back to the
			// current page domain; we only override the port/protocol so it dials back
			// through the proxy over wss/443 instead of the local dev port.
			clientPort: 443,
			protocol: 'wss'
		}
	},
	plugins: [
		tailwindcss(),
		websocketPlugin,
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) => filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},

			// adapter-auto only supports some environments, see https://svelte.dev/docs/kit/adapter-auto for a list.
			// If your environment is not supported, or you settled on a specific environment, switch out the adapter.
			// See https://svelte.dev/docs/kit/adapters for more information about adapters.
			adapter: adapter()
		})
	]
});
