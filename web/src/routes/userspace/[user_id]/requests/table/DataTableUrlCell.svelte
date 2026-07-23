<script lang="ts">
    import type {RequestUpdate} from "../../state.ts";
    import {methodColors} from "$lib/components/requests/colors";

    let {
        request,
    }: {
        request: RequestUpdate,
    } = $props();
</script>

<div class="flex min-w-0 flex-row gap-1">
    {#if request.kind === "websocket"}
        <span class="shrink-0 font-mono text-sm tracking-tight font-bold text-indigo-600">WS</span>
    {:else}
        <span class={(methodColors[request.method as keyof typeof methodColors] ?? "text-gray-600") + " shrink-0 font-mono text-sm tracking-tight font-bold"}>
            {request.method}
        </span>
    {/if}

    <span class="min-w-0 truncate text-sm tracking-tight font-mono text-gray-600 font-medium">
        {request.uri}
    </span>
</div>
