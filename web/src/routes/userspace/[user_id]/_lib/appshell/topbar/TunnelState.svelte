<script lang="ts">
    import {latestRequests, tunnelState} from "../../../state.ts";
    import {Popover, PopoverContent, PopoverTrigger} from "$lib/components/ui/popover";
    import {Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {ArrowRight, EthernetPort} from "@lucide/svelte";
    import RequestRow from "./RequestRow.svelte";
    import {Button} from "$lib/components/ui/button";

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
            <span class="text-neutral-500 font-mono text-xs font-bold uppercase">WB Tunnel Status:</span>
            <div
                    class={`flex flex-row items-center border rounded-sm px-1 font-mono text-xs ${classes}`}
            >
                {#if $tunnelState?.active}
                    <div class="w-2 h-2 rounded-full bg-green-500 mr-1">
                    </div>
                {/if}
                {$tunnelState?.active ? "Connected" : "Inactive"}
                {#if $tunnelState?.pingMs != null}
                    <span class="ml-1">({$tunnelState.pingMs}ms)</span>
                {/if}
            </div>
        </div>
    </PopoverTrigger>

    <PopoverContent class="w-xl">
        <div class="flex flex-col gap-1">
            {#if !$tunnelState?.active}
                <div>Start the tunnel using <code>wb tunnel</code></div>
            {:else}
                <div>
                    <span class="font-mono text-zinc-700 uppercase text-sm font-semibold">Incoming requests</span>

                    {#if $latestRequests.length === 0}
                        <Empty>
                            <EmptyHeader>
                                <EmptyMedia variant="icon">
                                    <EthernetPort />
                                </EmptyMedia>
                                <EmptyTitle>No requests in this session</EmptyTitle>
                                <EmptyDescription>Add a project and make your first HTTP-Request using your external domain!</EmptyDescription>
                            </EmptyHeader>
                        </Empty>
                    {:else}
                        <div class="flex flex-col gap-1 mt-2">
                            {#each $latestRequests as request (request.request_id)}
                                <RequestRow {request} />
                            {/each}
                        </div>
                        <Button
                                class="mt-2"
                                href="/tunnel"
                                variant="outline"
                        >
                            <ArrowRight />
                            All requests
                        </Button>
                    {/if}
                </div>
            {/if}
        </div>
    </PopoverContent>
</Popover>
