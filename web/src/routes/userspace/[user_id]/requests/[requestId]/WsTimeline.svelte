<script lang="ts">
    import {onMount} from "svelte";
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

<div class="flex flex-col gap-2">
    {#each frames as frame (frame.sequence)}
        {@const outgoing = frame.direction === "client_to_server"}
        {#if frame.opcode === "close"}
            <div class="flex justify-center py-1">
                <span class="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-500">
                    Verbindung geschlossen{frame.close_code ? ` · ${frame.close_code}` : ""}{frame.close_reason ? ` · ${frame.close_reason}` : ""}
                    <span class="ml-1 text-gray-400">{formatTime(frame.timestamp)}</span>
                </span>
            </div>
        {:else}
            <div class="flex" class:justify-end={outgoing}>
                <div
                        class="max-w-[80%] min-w-0 rounded-2xl px-3 py-2"
                        class:bg-emerald-600={outgoing}
                        class:text-white={outgoing}
                        class:rounded-br-sm={outgoing}
                        class:bg-gray-100={!outgoing}
                        class:text-gray-800={!outgoing}
                        class:rounded-bl-sm={!outgoing}
                >
                    <pre class="whitespace-pre-wrap break-all font-mono text-xs" class:opacity-80={frame.opcode === "binary"}>{frame.opcode === "text" ? frame.text : frame.binary_base64}</pre>
                    <div
                            class="mt-1 flex items-center gap-1.5 text-[10px]"
                            class:text-emerald-100={outgoing}
                            class:text-gray-400={!outgoing}
                    >
                        {#if frame.opcode === "binary"}<span class="uppercase">binary</span><span>·</span>{/if}
                        <span>{formatTime(frame.timestamp)}</span>
                        <span>·</span>
                        <span>{frame.size} B</span>
                    </div>
                </div>
            </div>
        {/if}
    {/each}
    {#if frames.length === 0}
        <div class="text-sm text-gray-400">Noch keine Frames.</div>
    {/if}
</div>
