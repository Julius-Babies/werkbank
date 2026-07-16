<script lang="ts">
    import {latestRequests, tunnelState} from "../../../state.ts";
    import {Popover, PopoverContent, PopoverTrigger} from "$lib/components/ui/popover";
    import {Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {ArrowRight, EthernetPort} from "@lucide/svelte";
    import RequestRow from "./RequestRow.svelte";
    import {Button} from "$lib/components/ui/button";
    import {_} from "svelte-i18n";

    let classes = $derived.by(() => {
        switch ($tunnelState?.active) {
            case null:
                return "text-neutral-500 border-none";
            case true:
                return "text-green-500 border-green-500 bg-green-500/10";
            case false:
                return "text-red-400 border-red-300 bg-red-400/10";
        }
    })
</script>

<Popover>
    <PopoverTrigger>
        <div class="flex flex-row items-center gap-1">
            <span class="text-neutral-500 font-mono text-xs font-bold uppercase">{$_("userspace.tunnel.status.label")}</span>
            <div
                    class={`flex flex-row items-center border rounded-sm px-1 font-mono text-xs ${classes}`}
            >
                {#if $tunnelState?.active}
                    <div class="w-2 h-2 rounded-full bg-green-500 mr-1">
                    </div>
                {/if}
                {$tunnelState?.active ? $_("userspace.tunnel.status.connected") : $_("userspace.tunnel.status.inactive")}
                {#if $tunnelState?.pingMs != null}
                    <span class="ml-1">({$tunnelState.pingMs}ms)</span>
                {/if}
            </div>
        </div>
    </PopoverTrigger>

    <PopoverContent class="w-xl px-0">
        <div class="flex flex-col gap-1">
            {#if !$tunnelState?.active}
                <div class="px-4">{$_("userspace.tunnel.inactive_hint")} <code>wb tunnel</code></div>
            {:else}
                <div>
                    <span class="px-4 font-mono text-zinc-700 uppercase text-sm font-semibold">{$_("userspace.tunnel.incoming_requests")}</span>

                    {#if $latestRequests.length === 0}
                        <Empty class="px-4">
                            <EmptyHeader>
                                <EmptyMedia variant="icon">
                                    <EthernetPort />
                                </EmptyMedia>
                                <EmptyTitle>{$_("userspace.tunnel.empty.title")}</EmptyTitle>
                                <EmptyDescription>{$_("userspace.tunnel.empty.description")}</EmptyDescription>
                            </EmptyHeader>
                        </Empty>
                    {:else}
                        <div class="flex flex-col gap-1 mt-2">
                            {#each $latestRequests as request (request.request_id)}
                                <RequestRow {request} />
                            {/each}
                        </div>
                        <Button
                                class="mx-4 mt-2"
                                href="/tunnel"
                                variant="outline"
                        >
                            <ArrowRight />
                            {$_("userspace.tunnel.all_requests")}
                        </Button>
                    {/if}
                </div>
            {/if}
        </div>
    </PopoverContent>
</Popover>
