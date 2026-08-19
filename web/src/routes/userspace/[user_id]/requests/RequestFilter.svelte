<script lang="ts">
    import {methodColors} from "$lib/components/requests/colors";
    import {FunnelIcon, FunnelXIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Tooltip, TooltipContent, TooltipTrigger} from "$lib/components/ui/tooltip";
    import {_} from "svelte-i18n";
    import {FILTER_METHODS, isNeutralQuery, neutralQuery, queryFromFilter, type RequestQuery} from "./filter.ts";
    import RequestQueryInput from "./RequestQueryInput.svelte";
    import {cn} from "$lib/utils";

    let {
        requestQuery = $bindable(),
        class: className,
    }: {
        requestQuery: RequestQuery,
        class?: string
    } = $props()

    let isNeutral = $derived(isNeutralQuery(requestQuery))

    function toggleMethod(method: string) {
        const methods = requestQuery.filter.filter_methods
        requestQuery = queryFromFilter({
            ...requestQuery.filter,
            filter_methods: methods.includes(method)
                ? methods.filter(m => m !== method)
                : [...methods, method],
        })
    }

    function toggleWebsockets() {
        requestQuery = queryFromFilter({...requestQuery.filter, only_websockets: !requestQuery.filter.only_websockets})
    }

    function reset() {
        requestQuery = neutralQuery()
    }

    const pillBase = "rounded-full border text-xs font-mono px-1.5 transition-colors duration-100"
    const inactivePill = "border-gray-300 hover:bg-gray-50"
    const activePill = "border-transparent bg-gray-800 text-white!"
    // A disabled pill must not swallow pointer events, otherwise the tooltip explaining why it is
    // disabled never opens.
    const disabledPill = "opacity-50 pointer-events-none"
</script>

<div class="flex flex-col gap-2 mb-2">
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

        <Tooltip disabled={!requestQuery.advanced}>
            <TooltipTrigger>
                {#snippet child({props})}
                    <div
                            {...props}
                            class={cn("flex flex-row items-center gap-1", requestQuery.advanced && "cursor-not-allowed")}
                    >
                        {#each FILTER_METHODS as method}
                            {@const active = requestQuery.filter.filter_methods.includes(method)}
                            <button
                                    type="button"
                                    disabled={requestQuery.advanced}
                                    onclick={() => toggleMethod(method)}
                                    class={cn(
                                        pillBase,
                                        active ? activePill : cn(inactivePill, methodColors[method as keyof typeof methodColors] ?? "text-gray-600"),
                                        requestQuery.advanced ? disabledPill : "cursor-pointer",
                                    )}
                            >{method}</button>
                        {/each}
                        <div class="w-px h-lh bg-gray-300 mx-1"></div>
                        <button
                                type="button"
                                disabled={requestQuery.advanced}
                                onclick={toggleWebsockets}
                                class={cn(
                                    pillBase,
                                    requestQuery.filter.only_websockets ? activePill : cn(inactivePill, "text-blue-700"),
                                    requestQuery.advanced ? disabledPill : "cursor-pointer",
                                )}
                        >WS</button>
                    </div>
                {/snippet}
            </TooltipTrigger>
            <TooltipContent>{$_("userspace.requests.filter.advanced")}</TooltipContent>
        </Tooltip>
    </div>

    <RequestQueryInput bind:requestQuery />
</div>
