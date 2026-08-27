<script lang="ts">
    import CardHead from "../../auth/_lib/CardHead.svelte";
    import type {ProxyErrorContext} from "../_lib/context";

    let {data}: {data: ProxyErrorContext} = $props();
</script>

<svelte:head>
    <title>Tunnel closed</title>
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
        Tunnel closed
    </div>
    <div>
        Something happened to the Werkbank tunnel while processing this request. It's possible that the tunnel-host
        went offline or had an error.
    </div>
</div>