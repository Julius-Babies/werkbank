<script lang="ts">
    import type {Project} from "../getProjects.ts";
    import {Button} from "$lib/components/ui/button";
    import {ArrowSquareOutIcon} from "phosphor-svelte";

    let {
        project,
    }: {
        project: Project,
    } = $props();
</script>

<div class="flex flex-row gap-2">
    <Button
            size="icon-sm"
            variant="ghost"
    >
        <ArrowSquareOutIcon />
    </Button>
</div>