<script lang="ts" module>
    export interface RequestsFilter {
        filter_methods: string[],
        only_websockets: boolean
    }

    export const defaultFilter: RequestsFilter = {
        filter_methods: [],
        only_websockets: false,
    }
</script>

<script lang="ts">
    import {methodColors} from "$lib/components/requests/colors";
    import {FunnelIcon} from "phosphor-svelte";

    const methods = [
        "GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"
    ]

    let {
        state = $bindable(),
    }: {
        state: RequestsFilter
    } = $props()
</script>

<div class="flex flex-row items-center gap-1 mb-2">
    <FunnelIcon />
    <div class="w-px h-lh bg-gray-300 mx-1"></div>
    {#each methods as method}
        <div class={"rounded-full border border-gray-300 text-xs font-mono px-1.5 cursor-pointer transition-colors duration-100 hover:bg-gray-50 " + (methodColors[method as keyof typeof methodColors] ?? "text-gray-600")}>{method}</div>
    {/each}
    <div class="w-px h-lh bg-gray-300 mx-1"></div>
    <div class="rounded-full border border-gray-300 text-xs font-mono px-1.5 cursor-pointer transition-colors duration-100 hover:bg-gray-50 text-blue-700">WS</div>
</div>