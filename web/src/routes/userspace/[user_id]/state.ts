import {writable} from "svelte/store";

export let user = writable<{
    username: string,
    email: string,
    avatar_url: string,
} | null>(null);

export let title = writable<string>("");