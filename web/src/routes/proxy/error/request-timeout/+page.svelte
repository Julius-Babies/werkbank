<script lang="ts">
    import CardHead from "../../auth/_lib/CardHead.svelte";
    import type {ProxyErrorContext} from "../_lib/context";

    let {data}: {data: ProxyErrorContext} = $props();
</script>

<svelte:head>
    <title>Request timeout</title>
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
        The request timed out
    </div>
    <div>
        This is most likely related to a service on the tunnel initiator. The tunnel it self seems to be working.
    </div>
</div>