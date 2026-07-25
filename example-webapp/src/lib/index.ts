// place files you want to import through the `$lib` alias in this folder.

import {writable} from "svelte/store";

export const DEFAULT_BASE_URL = "https://sample-webapp.julius-babies.werkbank.werkbank.space"
export const baseUrl = writable(DEFAULT_BASE_URL);