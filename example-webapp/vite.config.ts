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
