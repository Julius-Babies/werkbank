<script lang="ts">
    import type {RequestUpdate} from "../../../state.ts";
    import {Loader2} from "@lucide/svelte";
    import {_} from "svelte-i18n";
    import {methodColors} from "$lib/components/requests/colors";

    let {
        request,
    }: {
        request: RequestUpdate,
    } = $props();

    function statusColor(status: number): string {
        if (status >= 100 && status < 200) return "text-sky-600";     // Informational
        if (status >= 200 && status < 300) return "text-green-600";   // Success
        if (status >= 300 && status < 400) return "text-amber-600";   // Redirect
        if (status >= 400 && status < 500) return "text-orange-600";  // Client Error
        if (status >= 500 && status < 600) return "text-red-600";     // Server Error

        return "text-slate-600";
    }

    let duration = $derived.by(() => {
        if (request.completed_at != null) {
            return request.completed_at - request.started_at;
        }
        return null;
    });

    function formatDuration(ms: number): string {
        if (ms < 1000) return `${ms}ms`;
        return `${(ms / 1000).toFixed(2)}s`;
    }
</script>

<div class="flex flex-row items-center gap-2">
    <span class={(methodColors[request.method as keyof typeof methodColors] ?? "text-gray-600") + " font-mono text-xs font-bold"}>
        {request.method}
    </span>
    <div class="flex flex-row items-center gap-1 flex-1">
        {#if request.target}
            <div class="flex flex-row items-center gap-1 rounded-full border border-gray-300 px-1">
                <img src="/api/projects/{request.target.project_id}/icon" alt="project icon" class="size-3 rounded-sm" />
                <span class="text-xs text-gray-500">{request.target.project_name}</span>
                {#if request.target.service_name}
                    <span class="pb-0.5 text-xs text-gray-500">/</span>
                    <span class="pb-0.5 text-xs text-gray-500">{request.target.service_name}</span>
                {/if}
            </div>
        {/if}
        <span class="text-gray-700 text-xs line-clamp-1 text-ellipsis">{request.uri}</span>
    </div>

    <div class="font-mono text-xs font-semibold flex flex-row items-center gap-2">
        {#if duration != null}
            <span class="text-gray-500">{formatDuration(duration)}</span>
        {/if}
        {#if request.error}
            <span class="text-red-600 uppercase">{$_("userspace.tunnel.request.error")}</span>
        {:else if request.status_code}
            <span class={statusColor(request.status_code)}>{request.status_code}</span>
        {:else}
            <div class="animate-spin"><Loader2 size={12} /></div>
        {/if}
    </div>
</div>