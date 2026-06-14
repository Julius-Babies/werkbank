<script lang="ts">
    import {title} from "../state.ts";
    import AccessKeys from "./_access_keys/AccessKeys.svelte";
    import {_} from "svelte-i18n";

    $effect(() => {
        title.set($_("userspace.settings.title"))
    })
</script>

<div class="flex flex-col overflow-y-auto gap-4">
    <div class="flex flex-row justify-between w-full">
        <h1 class="text-3xl font-bold flex-1">{$_("userspace.settings.title")}</h1>
    </div>

    <div>
        <h2 class="text-2xl font-bold flex-1">{$_("userspace.settings.access.title")}</h2>
        <p class="text-sm text-gray-500">{$_("userspace.settings.access.description")}</p>

        <AccessKeys />

    </div>

</div>