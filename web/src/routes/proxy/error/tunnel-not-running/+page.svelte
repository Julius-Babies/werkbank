<script lang="ts">
    import {onMount} from "svelte";
    import CardHead from "../../auth/_lib/CardHead.svelte";
    import {WerkbankLogo} from "$lib/components/logo";
    import type {ProxyErrorContext} from "../_lib/context";

    let {data}: {data: ProxyErrorContext} = $props();

    const CHECK_INTERVAL_SECONDS = 5;

    let lastCheck = $state(Date.now());

    let pollState: "polling" | "success" = $state("polling");
    let pollInterval = $state<number | null>(null);

    onMount(() => {
        // Without an owner there is nothing to ask about, and polling would just 400 in a loop.
        if (!data.ownerId) return;

        pollInterval = setInterval(async () => {
            const response = await fetch(
                "/api/webapp/tunnel/error-page/tunnel-state?user_id=" + data.ownerId,
            );
            if (!response.ok) return

            lastCheck = Date.now();

            // The endpoint answers with `is_active`; reading `active` here silently never
            // matched, so the page polled forever with the tunnel already back up.
            const responseData = await response.json();
            if (responseData.is_active) {
                pollState = "success";
                if (pollInterval) clearInterval(pollInterval);
                pollInterval = null;
                setTimeout(() => {
                    window.location.reload();
                }, 1000);
            }
        }, CHECK_INTERVAL_SECONDS * 1000);

        return () => {
            if (pollInterval) clearInterval(pollInterval);
        }
    });
</script>

<svelte:head>
    <title>Tunnel not active</title>
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
        Tunnel not active
    </div>
    <div>
        The werkbank tunnel ist not connected. Start the tunnel using the <code>wb tunnel</code> command and
        check the hosts internet connection.
    </div>

    <div class="mt-4">
        <div class="flex flex-row items-center gap-2.5">
            <WerkbankLogo
                    class="size-10"
                    color={pollState === "polling" ? "red" : "green"}
                    state={pollState === "polling" ? "loading" : "visible"}
            />
            <div class="flex flex-col">
                <h2>{pollState === "polling" ? "Waiting for tunnel" : "Tunnel is active"}</h2>
                <div class="text-sm text-gray-500">
                    {#if pollState === "polling"}
                        Last check: {new Date(lastCheck).toLocaleTimeString()} (every {CHECK_INTERVAL_SECONDS} seconds)
                    {:else}
                        Reloading page...
                    {/if}
                </div>
            </div>
        </div>
    </div>
</div>