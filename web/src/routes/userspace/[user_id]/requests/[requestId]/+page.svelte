<script lang="ts">
    import {page} from "$app/state";
    import Page from "../../_lib/appshell/page/Page.svelte";
    import PageHead from "../../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../../_lib/appshell/page/PageTitle.svelte";
    import {Button} from "$lib/components/ui/button";
    import {ArrowLeftIcon} from "phosphor-svelte";
    import PageContent from "../../_lib/appshell/page/PageContent.svelte";
    import {requests} from "../requests.ts";
    import {untrack} from "svelte";
    import {methodColors, statusColor} from "$lib/components/requests/colors";
    import ContentLoading from "../../_lib/appshell/page/ContentLoading.svelte";
    import {fromRequestUpdate, getRequest, type Request} from "./request.ts";
    import HeaderTable from "./HeaderTable.svelte";
    import Body from "./Body.svelte";
    import WsTimeline from "./WsTimeline.svelte";
    import {ArrowDown, ArrowUp} from "@lucide/svelte";
    import {title} from "../../state.ts";

    let requestId = $derived(page.params.requestId)

    let request: "loading" | Request = $state("loading")

    let requestBodySize = $state(0)
    let responseBodySize = $state(0)

    $effect(() => {
        title.set(request === "loading" ? "Request " + requestId : request.request.method + " " + request.request.uri.slice(0, 100))
    })

    $effect(() => {
        const foundRequest = $requests.find(r => r.request_id === requestId)
        if (foundRequest) request = fromRequestUpdate(foundRequest) ?? "loading"
    })

    $effect(() => {
        if (requestId) getRequest(requestId)
            .then(result => {
                if (result === null) return
                untrack(() => {
                    request = result.request
                })

                requestBodySize = result.requestBodySize
                responseBodySize = result.responseBodySize
            })
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
                <span class={(methodColors[request.request.method as keyof typeof methodColors] ?? "text-gray-600")}>{request.request.method}</span>
                <span>{request.request.uri}</span>
            {/if}
        </PageTitle>
    </PageHead>

    <PageContent>
        {#if request !== "loading"}
            <div class="flex flex-row items-center gap-2">
                <img src="/api/projects/{request.project.project_id}/icon" alt="project icon" class="size-5 rounded-sm" />
                <div class="flex flex-row gap-1 items-baseline">
                    <span class="font-medium text-gray-600">{request.project.project_name}</span>
                    {#if request.service.service_key}
                        <span class="font-semibold text-gray-400">{request.service.service_key}</span>
                    {/if}
                </div>
            </div>

            {#if request.kind === "websocket"}
                <div class="flex flex-row items-center gap-3 pt-4 font-mono text-sm">
                    <span class="flex flex-row items-center gap-0.5 text-emerald-600" title="ausgehend">
                        <ArrowUp size={16} />{request.ws_frames_sent}
                    </span>
                    <span class="flex flex-row items-center gap-0.5 text-sky-600" title="eingehend">
                        <ArrowDown size={16} />{request.ws_frames_received}
                    </span>
                </div>

                <div class="pt-4">
                    <h2 class="font-heading font-semibold text-gray-800 uppercase pb-2">Messages</h2>
                    <WsTimeline requestId={request.request_id} />
                </div>
            {:else}
            <div class="flex max-xl:flex-col xl:flex-row gap-2 pt-4">
                <div class="flex-1 min-w-0 bg-zinc-50 p-4 rounded-sm overflow-hidden">
                    <h2 class="font-heading font-semibold text-gray-800 uppercase">Request</h2>
                    <HeaderTable headers={request.request.headers} />

                    {#if requestBodySize > 0}
                        <Body
                                class="mt-2"
                                request={request}
                                bodySize={requestBodySize}
                                type="request"
                        />
                    {/if}
                </div>
                <div
                        class="flex-1 min-w-0 p-4 rounded-sm overflow-hidden"
                        class:bg-zinc-100={request.response?.type === "success"}
                        class:bg-red-100={request.response?.type === "error"}
                        class:text-gray-800={request.response?.type === "success"}
                        class:text-red-800={request.response?.type === "error"}
                >
                    <div class="flex flex-row items-center justify-between">
                        <h2 class="font-heading font-semibold uppercase">Response</h2>
                        {#if request.response?.type === "success"}
                            <h2 class={"font-heading font-semibold uppercase " + statusColor(request.response.status_code)}>{request.response.status_code}</h2>
                        {/if}
                    </div>
                    {#if request.response?.type === "success"}
                        <HeaderTable headers={request.response.headers} />

                        {#if responseBodySize > 0}
                            <Body
                                    class="mt-2"
                                    request={request}
                                    bodySize={responseBodySize}
                                    type="response"
                            />
                        {/if}

                    {:else if request.response?.type === "error"}
                        <div class="text-red-800">
                            {request.response.error}
                        </div>
                    {/if}
                </div>
            </div>
            {/if}
        {:else}
            <ContentLoading />
        {/if}
    </PageContent>
</Page>