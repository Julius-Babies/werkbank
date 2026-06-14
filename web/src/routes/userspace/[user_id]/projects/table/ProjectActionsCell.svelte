<script lang="ts">
    import type {Project} from "../getProjects.ts";
    import {Button} from "$lib/components/ui/button";
    import {ArrowSquareOutIcon} from "phosphor-svelte";
    import {get} from "svelte/store";
    import {user} from "../../state.ts";
    import {env} from "$env/dynamic/public";

    let {
        project,
    }: {
        project: Project,
    } = $props();
</script>

<div class="flex flex-row gap-2">
    <Button
            href={"https://" + project.project_key + "." + get(user)!.username.toLowerCase() + "." + env.PUBLIC_BASE_DOMAIN}
            target="_blank"
            size="icon-sm"
            variant="ghost"
    >
        <ArrowSquareOutIcon />
    </Button>
</div>