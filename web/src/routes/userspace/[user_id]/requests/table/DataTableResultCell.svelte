<script lang="ts">
    import type {RequestUpdate} from "../../state";
    import {statusColor} from "$lib/components/requests/colors";

    let {
        request,
    }: {
        request: RequestUpdate,
    } = $props();

    let requestDuration = $derived.by(() => {
        if (!request.status_code || !request.completed_at) return null
        return request.completed_at - request.started_at
    })
</script>

{#if request.error}
    <span class="text-red-500 text-sm uppercase font-medium font-mono">ERR</span>
{:else if request.status_code}
    <div class="flex flex-row items-baseline gap-1">
        <span class={statusColor(request.status_code) + " font-semibold"}>{request.status_code}</span>
        <span class="text-gray-600 text-xs tracking-tighter font-light">{requestDuration !== null ? `${requestDuration}ms` : 'N/A'}</span>
    </div>
{/if}