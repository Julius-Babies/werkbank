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
    <title>No response</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if projectId}
        <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />
    {/if}

    <div class="text-4xl">
        The tunnel host didn't respond
    </div>
    <div>
        The tunnel is connected, but the tunnel host didn't start sending a response within 30 seconds.
        The targeted service may be stuck, overloaded or waiting on something that never completes.
    </div>
</div>
