<script lang="ts">
    import type {RequestUpdate} from "../../state.ts";

    let {
        request,
    }: {
        request: RequestUpdate
    } = $props();
</script>

{#if request.target}
    <div class="flex w-max flex-row items-center gap-1">
        <img src="/api/projects/{request.target.project_id}/icon" alt="project icon" class="size-8 rounded-sm" />
        <div class="flex flex-col">
            <span>{request.target.project_name}</span>
            {#if request.target.service_name}
                <span class="text-xs font-semibold text-gray-500">{request.target.service_name}</span>
            {/if}
        </div>
    </div>
{/if}