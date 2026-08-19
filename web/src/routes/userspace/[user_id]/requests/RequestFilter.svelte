<script lang="ts">
    import {methodColors} from "$lib/components/requests/colors";
    import {FunnelIcon, FunnelXIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Tooltip, TooltipContent, TooltipTrigger} from "$lib/components/ui/tooltip";
    import {_} from "svelte-i18n";
    import {FILTER_METHODS, isNeutralQuery, neutralQuery, queryFromFilter, type RequestQuery} from "./filter.ts";

    let {
        state = $bindable(),
    }: {
        state: RequestQuery
    } = $props()

    let isNeutral = $derived(isNeutralQuery(state))

    function toggleMethod(method: string) {
        const methods = state.filter.filter_methods
        state = queryFromFilter({
            ...state.filter,
            filter_methods: methods.includes(method)
                ? methods.filter(m => m !== method)
                : [...methods, method],
        })
    }

    function toggleWebsockets() {
        state = queryFromFilter({...state.filter, only_websockets: !state.filter.only_websockets})
    }

    function reset() {
        state = neutralQuery()
    }

    const pillBase = "rounded-full border text-xs font-mono px-1.5 transition-colors duration-100"
    const inactivePill = "border-gray-300 hover:bg-gray-50"
    const activePill = "border-transparent bg-gray-800 text-white!"
    // A disabled pill must not swallow pointer events, otherwise the tooltip explaining why it is
    // disabled never opens.
    const disabledPill = "opacity-50 pointer-events-none"
</script>

<div class="flex flex-col gap-1 mb-2">
    <div class="flex flex-row items-center gap-1">
        <Button
                variant="ghost"
                size="icon-sm"
                onclick={reset}
                disabled={isNeutral}
                aria-label={$_("userspace.requests.filter.reset")}
        >
            {#if isNeutral}
                <FunnelIcon />
            {:else}
                <FunnelXIcon />
            {/if}
        </Button>
        <div class="w-px h-lh bg-gray-300 mx-1"></div>

        <Tooltip disabled={!state.advanced}>
            <TooltipTrigger>
                {#snippet child({props})}
                    <div
                            {...props}
                            class="flex flex-row items-center gap-1 {state.advanced ? 'cursor-not-allowed' : ''}"
                    >
                        {#each FILTER_METHODS as method}
                            {@const active = state.filter.filter_methods.includes(method)}
                            <button
                                    type="button"
                                    disabled={state.advanced}
                                    onclick={() => toggleMethod(method)}
                                    class={[
                                        pillBase,
                                        active ? activePill : inactivePill + " " + (methodColors[method as keyof typeof methodColors] ?? "text-gray-600"),
                                        state.advanced ? disabledPill : "cursor-pointer",
                                    ]}
                            >{method}</button>
                        {/each}
                        <div class="w-px h-lh bg-gray-300 mx-1"></div>
                        <button
                                type="button"
                                disabled={state.advanced}
                                onclick={toggleWebsockets}
                                class={[
                                    pillBase,
                                    state.filter.only_websockets ? activePill : inactivePill + " text-blue-700",
                                    state.advanced ? disabledPill : "cursor-pointer",
                                ]}
                        >WS</button>
                    </div>
                {/snippet}
            </TooltipTrigger>
            <TooltipContent>{$_("userspace.requests.filter.advanced")}</TooltipContent>
        </Tooltip>
    </div>

    <div class="flex flex-row items-baseline gap-2 text-xs">
        <span class="text-gray-500">{$_("userspace.requests.filter.query")}</span>
        {#if state.query}
            <code class="font-mono break-all select-all">{state.query}</code>
        {:else}
            <span class="text-gray-400">{$_("userspace.requests.filter.neutral")}</span>
        {/if}
    </div>
</div>
