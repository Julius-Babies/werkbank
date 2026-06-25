<script lang="ts">
    import {page} from "$app/state";
    import {onMount} from "svelte";
    import CardHead from "../../auth/_lib/CardHead.svelte";

    let projectId = $state("");
    let ownerUsername = $state("");
    let ownerAvatarUrl = $state("");
    let serviceNameRaw = $state("null");

    onMount(() => {
        projectId = page.url.searchParams.get("project_id")!;
        ownerUsername = page.url.searchParams.get("owner_username")!;
        ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url")!;
        serviceNameRaw = page.url.searchParams.get("service_name")!;
    });

    const serviceName = $derived(serviceNameRaw === "null" ? null : serviceNameRaw)
    const displayServiceName = $derived(serviceName ?? "unknown service")
</script>

<svelte:head>
    <title>Service not running</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if projectId}
        <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />
    {/if}

    <div class="text-4xl">
        Failed to connect to {displayServiceName}
    </div>
    <div>
        The service {displayServiceName} didn't respond. Maybe it's offline or not taking in requests at the moment.
    </div>
</div>