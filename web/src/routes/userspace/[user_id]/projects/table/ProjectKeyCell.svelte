<script lang="ts">
    import type {Project} from "../getProjects.ts";

    let {
        project,
    }: {
        project: Project
    } = $props();
</script>

<div class="flex min-w-0 flex-row gap-2 items-center text-neutral-700">
    <img src="/api/projects/{project.project_id}/icon" alt="project icon" class="w-8 h-8 shrink-0 rounded-sm" />

    <div class="flex min-w-0 flex-col">
        <span class="truncate">{project.project_name}</span>
        <span class="truncate text-xs font-semibold">{project.project_key}</span>
    </div>
</div>