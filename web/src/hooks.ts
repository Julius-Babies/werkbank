import type {Reroute} from "@sveltejs/kit";

import {env} from "$env/dynamic/public";

export const reroute: Reroute = ({ url }) => {
    const host = url.hostname;
    const suffix = env.PUBLIC_BASE_DOMAIN;

    if (!host.endsWith(suffix)) {
        return;
    }

    const subdomainPart = url.hostname.slice(0, -suffix.length-1);

    if (!subdomainPart) {
        return;
    }

    const userId = subdomainPart.split('.').pop();

    if (!userId) {
        return;
    }

    if (url.pathname.startsWith(`/userspace/${userId}`)) {
        return;
    }

    return `/userspace/${userId}${url.pathname}`;
};