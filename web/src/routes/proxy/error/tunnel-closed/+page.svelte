<script lang="ts">
    import {page} from "$app/state";
    import CardHead from "../../auth/_lib/CardHead.svelte";

    const projectId = page.url.searchParams.get("project_id")!;
    const ownerUsername = page.url.searchParams.get("owner_username")!;
    const ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url")!;
</script>

<svelte:head>
    <title>Tunnel closed</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />

    <div class="text-4xl">
        Tunnel closed
    </div>
    <div>
        Something happened to the Werkbank tunnel while processing this request. It's possible that the tunnel-host
        went offline or had an error.
    </div>
</div>