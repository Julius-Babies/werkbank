<script lang="ts">
    import {page} from "$app/state";
    import Page from "../../_lib/appshell/page/Page.svelte";
    import PageHead from "../../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../../_lib/appshell/page/PageTitle.svelte";
    import {Button} from "$lib/components/ui/button";
    import {ArrowLeftIcon} from "phosphor-svelte";
    import PageContent from "../../_lib/appshell/page/PageContent.svelte";
    import {requests} from "../requests.ts";
    import {onMount} from "svelte";
    import {methodColors} from "$lib/components/requests/colors";
    import type {RequestUpdate} from "../../state.ts";
    import { Loader2 } from "@lucide/svelte";

    let requestId = page.params.requestId

    let request: "loading" | RequestUpdate = $state($requests.find(r => r.request_id === requestId) ?? "loading")

    $effect(() => {
        const foundRequest = $requests.find(r => r.request_id === requestId)
        if (foundRequest) request = foundRequest
    })

    onMount(() => {
        if (request === "loading") {
            // download data
        }
    })
</script>

<Page>
    <Button class="w-fit" size="sm" variant="ghost" onclick={() => history.back()}>
        <ArrowLeftIcon />
        Tunnel-Requests
    </Button>
    <PageHead>
        <PageTitle>
            {#if request === "loading"}
                Request {requestId}
            {:else}
                <span class={(methodColors[request.method as keyof typeof methodColors] ?? "text-gray-600") + " font-mono"}>{request.method}</span>
                <span>{request.uri}</span>
            {/if}
        </PageTitle>
    </PageHead>

    <PageContent>
        {#if request !== "loading"}
            {#if request.target}
                <div class="flex flex-row items-center gap-2">
                    <img src="/api/projects/{request.target.project_id}/icon" alt="project icon" class="size-5 rounded-sm" />
                    <div class="flex flex-row gap-1 items-baseline">
                        <span class="font-medium text-gray-600">{request.target.project_name}</span>
                        {#if request.target.service_name}
                            <span class="font-semibold text-gray-400">{request.target.service_name}</span>
                        {/if}
                    </div>
                </div>
            {/if}
        {:else}
            <div class="flex flex-col gap-2 items-center justify-center">
                <div class="animate-spin"><Loader2 size={24} /></div>
            </div>
        {/if}
    </PageContent>
</Page>