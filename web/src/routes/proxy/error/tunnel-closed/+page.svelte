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
    <title>Tunnel closed</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if projectId}
        <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />
    {/if}

    <div class="text-4xl">
        Tunnel closed
    </div>
    <div>
        Something happened to the Werkbank tunnel while processing this request. It's possible that the tunnel-host
        went offline or had an error.
    </div>
</div>