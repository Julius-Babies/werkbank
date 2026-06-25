<script lang="ts">
    import {page} from "$app/state";
    import {onMount} from "svelte";
    import CardHead from "../../auth/_lib/CardHead.svelte";

    let projectId = $state("");
    let ownerUsername = $state("");
    let ownerAvatarUrl = $state("");

    onMount(() => {
        projectId = page.url.searchParams.get("project_id")!;
        ownerUsername = page.url.searchParams.get("owner_username")!;
        ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url")!;
    });
</script>

<svelte:head>
    <title>Request timeout</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if projectId}
        <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />
    {/if}

    <div class="text-4xl">
        The request timed out
    </div>
    <div>
        This is most likely related to a service on the tunnel initiator. The tunnel it self seems to be working.
    </div>
</div>