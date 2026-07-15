<script lang="ts">
    import type {RequestUpdate} from "../../state.ts";

    let {
        request,
    }: {
        request: RequestUpdate
    } = $props();
</script>

{#if request.target}
    <div class="flex w-max flex-row items-center gap-2.5">
        <img src="/api/projects/{request.target.project_id}/icon" alt="project icon" class="size-5 rounded-sm" />
        <div class="flex flex-row gap-1 items-baseline">
            <span class="text-sm font-medium text-gray-600">{request.target.project_name}</span>
            {#if request.target.service_name}
                <span class="text-xs font-semibold text-gray-400">{request.target.service_name}</span>
            {/if}
        </div>
    </div>
{/if}