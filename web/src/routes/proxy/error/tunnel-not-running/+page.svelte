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
    <title>Tunnel not active</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if projectId}
        <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />
    {/if}

    <div class="text-4xl">
        Tunnel not active
    </div>
    <div>
        The werkbank tunnel ist not connected. Start the tunnel using the <code>wb tunnel</code> command and
        check the hosts internet connection.
    </div>
</div>