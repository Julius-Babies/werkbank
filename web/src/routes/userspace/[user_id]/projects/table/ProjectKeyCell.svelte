<script lang="ts">
    import type {Project} from "../getProjects.ts";

    let {
        project,
    }: {
        project: Project
    } = $props();
</script>

<div class="flex flex-row gap-2 items-center text-neutral-700">
    <img src="/api/projects/{project.project_id}/icon" alt="project icon" class="w-8 h-8 rounded-sm" />

    <div class="flex flex-col">
        <span>{project.project_name}</span>
        <span class="text-xs font-semibold">{project.project_key}</span>
    </div>
</div>