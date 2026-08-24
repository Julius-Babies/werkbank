<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {Button} from "$lib/components/ui/button";
    import {TrashIcon} from "phosphor-svelte";
    import {countCachedRequests} from "../../requests/cache.ts";
    import {clearCachedRequests} from "../../requests/requests.svelte.ts";

    let cached: number | null = $state(null);
    let clearing = $state(false);

    function refresh() {
        countCachedRequests().then((count) => cached = count);
    }

    onMount(refresh);

    async function clear() {
        clearing = true;
        try {
            await clearCachedRequests();
        } finally {
            clearing = false;
            refresh();
        }
    }
</script>

<div class="flex flex-col gap-2">
    <div>
        <h3 class="text-xl font-bold mt-2">{$_("userspace.settings.request_cache.title")}</h3>
        <p class="text-sm text-gray-500">{$_("userspace.settings.request_cache.description")}</p>
    </div>

    <p class="text-sm text-gray-500">
        {#if cached === null}
            {$_("userspace.settings.request_cache.counting")}
        {:else}
            {$_("userspace.settings.request_cache.count", {values: {count: cached}})}
        {/if}
    </p>

    <Button variant="outline" class="w-fit" disabled={clearing || cached === 0} onclick={clear}>
        <TrashIcon />
        {clearing ? $_("userspace.settings.request_cache.clearing") : $_("userspace.settings.request_cache.clear")}
    </Button>
</div>
