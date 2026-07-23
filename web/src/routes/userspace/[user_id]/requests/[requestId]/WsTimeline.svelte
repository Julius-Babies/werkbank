<script lang="ts">
    import {onMount} from "svelte";
    import {ArrowDown, ArrowUp} from "@lucide/svelte";
    import type {WsFrame} from "../../state.ts";
    import {watchedFrames} from "../../state.ts";
    import {watchFrames, unwatchFrames} from "../../webappSocket.ts";
    import {getFrames} from "./request.ts";

    let {requestId}: {requestId: string} = $props();

    let framesBySequence = $state<Map<number, WsFrame>>(new Map());

    function merge(list: WsFrame[]) {
        if (list.length === 0) return;
        const next = new Map(framesBySequence);
        for (const frame of list) next.set(frame.sequence, frame);
        framesBySequence = next;
    }

    let frames = $derived([...framesBySequence.values()].sort((a, b) => a.sequence - b.sequence));

    onMount(() => {
        getFrames(requestId).then(merge);
        // Subscribe to live frames; if the connection is already closed the backend simply sends none.
        watchFrames(requestId);
        const unsubscribe = watchedFrames.subscribe(merge);
        return () => {
            unsubscribe();
            unwatchFrames();
        };
    });

    function formatTime(ts: number): string {
        const date = new Date(ts);
        return date.toLocaleTimeString(undefined, {hour12: false}) + "." + String(date.getMilliseconds()).padStart(3, "0");
    }
</script>

<div class="flex flex-col gap-1">
    {#each frames as frame (frame.sequence)}
        {@const outgoing = frame.direction === "client_to_server"}
        <div
                class="flex flex-row gap-2 rounded-sm border-l-2 px-2 py-1.5"
                class:border-emerald-400={outgoing}
                class:bg-emerald-50={outgoing}
                class:border-sky-400={!outgoing}
                class:bg-sky-50={!outgoing}
        >
            <div class={"shrink-0 pt-0.5 " + (outgoing ? "text-emerald-600" : "text-sky-600")}>
                {#if outgoing}<ArrowUp size={14} />{:else}<ArrowDown size={14} />{/if}
            </div>
            <div class="min-w-0 flex-1">
                <div class="flex flex-row items-center gap-2 font-mono text-xs text-gray-500">
                    <span class="font-semibold uppercase">{frame.opcode}</span>
                    <span>{formatTime(frame.timestamp)}</span>
                    <span>{frame.size} B</span>
                </div>
                {#if frame.opcode === "text"}
                    <pre class="mt-0.5 whitespace-pre-wrap break-all font-mono text-xs text-gray-800">{frame.text}</pre>
                {:else if frame.opcode === "binary"}
                    <pre class="mt-0.5 whitespace-pre-wrap break-all font-mono text-xs text-gray-500">{frame.binary_base64}</pre>
                {:else if frame.opcode === "close"}
                    <div class="mt-0.5 font-mono text-xs text-gray-600">
                        Close {frame.close_code ?? ""}{frame.close_reason ? ` – ${frame.close_reason}` : ""}
                    </div>
                {/if}
            </div>
        </div>
    {/each}
    {#if frames.length === 0}
        <div class="text-sm text-gray-400">Noch keine Frames.</div>
    {/if}
</div>
