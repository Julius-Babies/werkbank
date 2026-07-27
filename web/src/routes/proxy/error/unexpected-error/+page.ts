// Server-rendered only: the API injects the tunnel details into the prerendered HTML by replacing a
// placeholder token. Disabling client-side rendering keeps hydration from overwriting that injected
// markup with the original placeholder from the bundle.
export const csr = false;
