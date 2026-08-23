import {locale, waitLocale} from "svelte-i18n";
import {browser} from "$app/environment";
import "$lib/localization/i18n";
import type {LayoutLoad} from "./$types";

export const load: LayoutLoad = async () => {
    if (browser) {
        locale.set(window.navigator.language);
    }

    // Messages are registered lazily, so block rendering until the dictionary
    // for the active locale is loaded. Otherwise formatting throws.
    await waitLocale();
};
