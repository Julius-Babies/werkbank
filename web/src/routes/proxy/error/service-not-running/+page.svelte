<script lang="ts">
    import {page} from "$app/state";
    import CardHead from "../../auth/_lib/CardHead.svelte";

    const projectId = page.url.searchParams.get("project_id")!;
    const ownerUsername = page.url.searchParams.get("owner_username")!;
    const ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url")!;
    const serviceNameRaw = page.url.searchParams.get("service_name")!;
    const serviceName = $derived(serviceNameRaw === "null" ? null : serviceNameRaw)
    const displayServiceName = $derived(serviceName ?? "unknown service")
</script>

<svelte:head>
    <title>Service not running</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    <CardHead class="self-start" {projectId} {ownerUsername} ownerProfileIcon={ownerAvatarUrl} />

    <div class="text-4xl">
        Failed to connect to {displayServiceName}
    </div>
    <div>
        The service {displayServiceName} didn't respond. Maybe it's offline or not taking in requests at the moment.
    </div>
</div>