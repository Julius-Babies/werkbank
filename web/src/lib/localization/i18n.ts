import {addMessages, getLocaleFromNavigator, init, register} from "svelte-i18n";
import {de as requestsDe, en as requestsEn} from "../../routes/userspace/[user_id]/requests/lang";
import {de as tunnelDe, en as tunnelEn} from "../../routes/userspace/[user_id]/_lib/appshell/topbar/lang";

register("en", () => import("./en.json"))
register("de", () => import("./de.json"))

// Route-scoped translations are kept next to their routes and merged in here
// so the central lang files don't grow too large.
addMessages("en", requestsEn)
addMessages("en", tunnelEn)
addMessages("de", requestsDe)
addMessages("de", tunnelDe)

init({
    fallbackLocale: "en",
    initialLocale: getLocaleFromNavigator(),
})
