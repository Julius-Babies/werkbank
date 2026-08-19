<script lang="ts">
    import {methodColors} from "$lib/components/requests/colors";
    import {FunnelIcon, FunnelXIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {defaultFilter, isDefaultFilter, type RequestsFilter} from "./filter.ts";

    const methods = [
        "GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"
    ]

    let {
        state = $bindable(),
    }: {
        state: RequestsFilter
    } = $props()

    let isDefault = $derived(isDefaultFilter(state))

    function toggleMethod(method: string) {
        if (state.filter_methods.includes(method)) {
            state.filter_methods = state.filter_methods.filter(m => m !== method)
        } else {
            state.filter_methods = [...state.filter_methods, method]
        }
    }

    function reset() {
        state = defaultFilter()
    }

    const pillBase = "rounded-full border text-xs font-mono px-1.5 cursor-pointer transition-colors duration-100"
    const inactivePill = "border-gray-300 hover:bg-gray-50"
    const activePill = "border-transparent bg-gray-800 text-white!"
</script>

<div class="flex flex-row items-center gap-1 mb-2">
    <Button
            variant="ghost"
            size="icon-sm"
            onclick={reset}
            disabled={isDefault}
            aria-label="Reset filter"
    >
        {#if isDefault}
            <FunnelIcon />
        {:else}
            <FunnelXIcon />
        {/if}
    </Button>
    <div class="w-px h-lh bg-gray-300 mx-1"></div>
    {#each methods as method}
        {@const active = state.filter_methods.includes(method)}
        <button
                type="button"
                onclick={() => toggleMethod(method)}
                class={pillBase + " " + (active ? activePill : inactivePill + " " + (methodColors[method as keyof typeof methodColors] ?? "text-gray-600"))}
        >{method}</button>
    {/each}
    <div class="w-px h-lh bg-gray-300 mx-1"></div>
    <button
            type="button"
            onclick={() => state.only_websockets = !state.only_websockets}
            class={pillBase + " " + (state.only_websockets ? activePill : inactivePill + " text-blue-700")}
    >WS</button>
</div>
