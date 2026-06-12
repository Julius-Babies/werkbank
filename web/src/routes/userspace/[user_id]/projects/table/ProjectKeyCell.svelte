<script lang="ts">
    import type {Project} from "../getProjects.ts";

    let {
        project,
    }: {
        project: Project
    } = $props();
</script>

{project.project_name} ({project.project_key})