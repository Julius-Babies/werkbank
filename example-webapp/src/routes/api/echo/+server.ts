import { json, type RequestHandler } from '@sveltejs/kit';

/**
 * Simple echo endpoint used to exercise the werkbank proxy.
 *
 * Every common HTTP method resolves here (via the configured base URL, which
 * points at the proxy and forwards back to this SvelteKit app). Each handler
 * returns a small JSON payload describing the request it received.
 */
function handle(method: string): RequestHandler {
	return async ({ request }) => {
		let body: unknown = null;
		if (method !== 'GET' && method !== 'HEAD') {
			const text = await request.text();
			if (text) {
				try {
					body = JSON.parse(text);
				} catch {
					body = text;
				}
			}
		}

		return json({
			method,
			message: `Handled ${method} request`,
			receivedBody: body,
			timestamp: new Date().toISOString()
		});
	};
}

export const GET = handle('GET');
export const POST = handle('POST');
export const PUT = handle('PUT');
export const PATCH = handle('PATCH');
export const DELETE = handle('DELETE');
