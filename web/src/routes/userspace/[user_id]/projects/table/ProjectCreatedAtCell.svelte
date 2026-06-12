<script lang="ts">
    import type {Project} from "../getProjects.ts";

    let {
        project,
    }: {
        project: Project
    } = $props();


    const options: Intl.DateTimeFormatOptions = {
        day: 'numeric',
        month: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    };

    let date = $derived(project.created_at.toLocaleDateString(undefined, options));
</script>

{date}