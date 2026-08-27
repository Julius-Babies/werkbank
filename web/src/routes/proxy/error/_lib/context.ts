/**
 * The project context the API appends to the error page URL it fetches, as it reaches the pages.
 *
 * Declared by hand instead of using the generated `PageData`: inferring that from a server `load`
 * needs SvelteKit's proxy modules, which are built through the TypeScript JS API. This project is on
 * TypeScript 7, whose Node API differs, so SvelteKit silently falls back to `LayoutServerData =
 * unknown` (the same reason plain `svelte-check` cannot run here). Empty strings stand for values
 * the API had nothing for.
 */
export type ProxyErrorContext = {
    projectId: string;
    ownerId: string;
    ownerUsername: string;
    ownerAvatarUrl: string;
    serviceName: string;
};
