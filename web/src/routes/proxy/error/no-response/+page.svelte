<script lang="ts">
    import CardHead from "../../auth/_lib/CardHead.svelte";
    import type {ProxyErrorContext} from "../_lib/context";

    let {data}: {data: ProxyErrorContext} = $props();
</script>

<svelte:head>
    <title>No response</title>
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
        The tunnel host didn't respond
    </div>
    <div>
        The tunnel is connected, but the tunnel host didn't start sending a response within 30 seconds.
        The targeted service may be stuck, overloaded or waiting on something that never completes.
    </div>
</div>
