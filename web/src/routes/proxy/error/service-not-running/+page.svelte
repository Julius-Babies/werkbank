<script lang="ts">
    import CardHead from "../../auth/_lib/CardHead.svelte";
    import type {ProxyErrorContext} from "../_lib/context";

    let {data}: {data: ProxyErrorContext} = $props();

    const displayServiceName = $derived(data.serviceName || "unknown service")
</script>

<svelte:head>
    <title>Service not running</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">

    {#if data.projectId}
        <CardHead
            class="self-start mb-2"
            projectId={data.projectId}
            ownerUsername={data.ownerUsername}
            ownerProfileIcon={data.ownerAvatarUrl}
        />
    {/if}

    <div class="text-4xl">
        Failed to connect to {displayServiceName}
    </div>
    <div>
        The service {displayServiceName} didn't respond. Maybe it's offline or not taking in requests at the moment.
    </div>
</div>