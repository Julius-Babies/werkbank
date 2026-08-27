import type {LayoutServerLoad} from "./$types";
import type {ProxyErrorContext} from "./_lib/context";

// Rendered per request instead of prerendered: the project context arrives as query parameters on
// the URL the API fetches these pages from, and a build-time render has no such URL to read.
export const prerender = false;

/**
 * The project context the API appends to the error page URL it fetches (see
 * `applyProjectContextForErrorPage` in ProxyPlugin.kt).
 *
 * A server load, because the browser cannot read these off its own URL: the fetched page is
 * re-served under the project's own subdomain, and that URL carries no query string at all. Going
 * through `load` puts the values into the hydration payload, which is the only route from the
 * fetching API to the running page.
 *
 * Values the API has nothing for are sent as the literal string "null", normalised here so no page
 * has to know that.
 */
export const load: LayoutServerLoad = ({url}): ProxyErrorContext => {
    const param = (name: string) => {
        const value = url.searchParams.get(name);
        return value === null || value === "null" ? "" : value;
    };

    return {
        projectId: param("project_id"),
        ownerId: param("owner_id"),
        ownerUsername: param("owner_username"),
        ownerAvatarUrl: param("owner_avatar_url"),
        serviceName: param("service_name"),
    };
};
