<script lang="ts">
    import {type RequestUpdate, title} from "../state.ts";
    import {onMount, untrack} from "svelte";
    import {goto, replaceState} from "$app/navigation";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {fetchRequests, requests} from "./requests.ts";
    import Page from "../_lib/appshell/page/Page.svelte";
    import ContentLoading from "../_lib/appshell/page/ContentLoading.svelte";
    import PageHead from "../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../_lib/appshell/page/PageTitle.svelte";
    import {createSvelteTable} from "$lib/components/ui/data-table";
    import {columns} from "./columns.ts";
    import {getCoreRowModel} from "@tanstack/table-core";
    import DataTable from "../_lib/appshell/page/DataTable.svelte";
    import {Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {ArrowBendDownRightIcon, FunnelXIcon, ListDashesIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import PageContent from "../_lib/appshell/page/PageContent.svelte";
    import RequestFilterComponent, { filterFromParams, filterToParams, type RequestsFilter } from "./RequestFilter.svelte";

    $effect(() => {
        title.set($_("userspace.requests.title"))
    })

    let isLoading = $state(true)

    onMount(() => {
        fetchRequests()
            .then(() => isLoading = false);
    })

    let currentFilter: RequestsFilter = $state(filterFromParams(page.url.searchParams))

    // Keep the active filter in the URL so it is restored when navigating back.
    $effect(() => {
        const query = filterToParams(currentFilter).toString()
        untrack(() => {
            replaceState(query ? `?${query}` : page.url.pathname, {})
        })
    })

    // TODO(websockets): proper WebSocket tracking does not exist yet. Until it
    // lands we approximate a WebSocket by its HTTP 101 (Switching Protocols)
    // upgrade handshake. Replace this once requests carry a real WS flag.
    function isWebsocketRequestWorkaround(request: RequestUpdate): boolean {
        return request.status_code === 101
    }

    let filteredRequests = $derived($requests.filter((request) => {
        if (currentFilter.filter_methods.length > 0 && !currentFilter.filter_methods.includes(request.method)) return false
        if (currentFilter.only_websockets && !isWebsocketRequestWorkaround(request)) return false
        return true
    }))

    function clearFilter() {
        currentFilter = {filter_methods: [], only_websockets: false}
    }

    let table = createSvelteTable({
        get data() {
            return filteredRequests
        },
        columns: columns(),
        getCoreRowModel: getCoreRowModel(),
        getRowId: (row: RequestUpdate) => row.request_id,
        enableRowSelection: false,

    })
</script>

<Page>
    <PageHead>
        <PageTitle>{$_("userspace.requests.title")}</PageTitle>
    </PageHead>

    <PageContent>
        {#if isLoading}
            <ContentLoading />
        {:else}
            <RequestFilterComponent bind:state={currentFilter} />
            <DataTable
                    {table}
                    cellClass="py-1.5"
                    onRowClick={(request: RequestUpdate) => goto(`/requests/${request.request_id}`)}
            >
                {#snippet empty()}
                    {#if $requests.length === 0}
                        <Empty>
                            <EmptyHeader>
                                <EmptyMedia variant="icon">
                                    <ListDashesIcon />
                                </EmptyMedia>
                                <EmptyTitle>{$_("userspace.requests.empty.title")}</EmptyTitle>
                                <EmptyDescription>{$_("userspace.requests.empty.description")}</EmptyDescription>
                            </EmptyHeader>

                            <EmptyContent>
                                <div class="flex flex-row gap-2">
                                    <Button href="/">
                                        <ArrowBendDownRightIcon />
                                        {$_("userspace.requests.empty.install")}
                                    </Button>
                                </div>
                            </EmptyContent>
                        </Empty>
                    {:else}
                        <Empty>
                            <EmptyHeader>
                                <EmptyMedia variant="icon">
                                    <FunnelXIcon />
                                </EmptyMedia>
                                <EmptyTitle>{$_("userspace.requests.empty.filtered.title")}</EmptyTitle>
                                <EmptyDescription>
                                    {$_("userspace.requests.empty.filtered.description", {values: {count: $requests.length}})}
                                </EmptyDescription>
                            </EmptyHeader>

                            <EmptyContent>
                                <div class="flex flex-row gap-2">
                                    <Button variant="outline" onclick={clearFilter}>
                                        <FunnelXIcon />
                                        {$_("userspace.requests.empty.filtered.clear")}
                                    </Button>
                                </div>
                            </EmptyContent>
                        </Empty>
                    {/if}
                {/snippet}
            </DataTable>
        {/if}
    </PageContent>
</Page>
