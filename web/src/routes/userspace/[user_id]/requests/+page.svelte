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
    import RequestFilterComponent from "./RequestFilter.svelte";
    import {neutralQuery, queryFromParams, queryFromString, queryToParams, type RequestQuery} from "./filter.ts";
    import {compileQuery} from "./query.ts";

    $effect(() => {
        title.set($_("userspace.requests.title"))
    })

    let isLoading = $state(true)

    onMount(() => {
        fetchRequests()
            .then(() => isLoading = false);
    })

    let currentQuery: RequestQuery = $state(queryFromParams(page.url.searchParams))

    // The query is the URL representation of the filter, so a shared or reopened link restores
    // exactly the filter that produced the visible list. Both directions run through the query both
    // sides last agreed on: whoever changed it writes to the other side, and neither can overwrite
    // a newer value of the other.
    let syncedQuery = page.url.searchParams.get("q") ?? ""

    $effect(() => {
        const query = currentQuery.query
        untrack(() => {
            if (query === syncedQuery) return

            syncedQuery = query
            replaceState(`?${queryToParams(currentQuery)}`, {})
        })
    })

    // Navigating to this page with another query — a link, back or forward — has to update the
    // filter: while the page stays mounted, the page state is what changes.
    $effect(() => {
        const query = page.url.searchParams.get("q") ?? ""
        untrack(() => {
            if (query === syncedQuery) return

            syncedQuery = query
            currentQuery = queryFromString(query)
        })
    })

    let filteredRequests = $derived.by(() => {
        const matches = compileQuery(currentQuery.query)
        return $requests.filter(matches)
    })

    function clearFilter() {
        currentQuery = neutralQuery()
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
            <RequestFilterComponent bind:requestQuery={currentQuery} class="mb-2" />
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
