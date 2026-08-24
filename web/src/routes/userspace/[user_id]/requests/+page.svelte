<script lang="ts">
    import {title} from "../state.ts";
    import {untrack} from "svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {loadOlderRequests, requestList, searchFurtherBack, setRequestQuery} from "./requests.svelte.ts";
    import Page from "../_lib/appshell/page/Page.svelte";
    import ContentLoading from "../_lib/appshell/page/ContentLoading.svelte";
    import PageHead from "../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../_lib/appshell/page/PageTitle.svelte";
    import {Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {ArrowBendDownRightIcon, FunnelXIcon, ListDashesIcon, MagnifyingGlassIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import PageContent from "../_lib/appshell/page/PageContent.svelte";
    import RequestFilterComponent from "./RequestFilter.svelte";
    import {neutralQuery, queryFromParams, queryFromString, queryToParams, type RequestQuery} from "./filter.ts";
    import RequestList from "./RequestList.svelte";

    $effect(() => {
        title.set($_("userspace.requests.title"))
    })

    let currentQuery: RequestQuery = $state(queryFromParams(page.url.searchParams))

    // The query is the URL representation of the filter, so a shared or reopened link restores
    // exactly the filter that produced the visible list. Both directions run through the query both
    // sides last agreed on: whoever changed it writes to the other side, and neither can overwrite
    // a newer value of the other.
    let syncedQuery = page.url.searchParams.get("q") ?? ""

    // `replaceState` from `$app/navigation` is shallow routing: it rewrites the address bar but
    // pins `page.url` to the last real navigation and remembers that pinned URL in the history
    // entry. Coming back to that entry would restore the URL without the query, so the filter has
    // to be written with a real navigation.
    $effect(() => {
        const query = currentQuery.query
        untrack(() => {
            if (query === syncedQuery) return

            syncedQuery = query
            // Typing in the filter navigates on every keystroke, so the entry must be replaced and
            // focus and scroll position must survive it.
            void goto(`?${queryToParams(currentQuery)}`, {replaceState: true, keepFocus: true, noScroll: true})
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

    // The list matches the query itself, so a keystroke re-matches the loaded requests once instead
    // of filtering them again for every live update that follows.
    $effect(() => {
        setRequestQuery(currentQuery.query)
    })

    // Nothing matching does not mean there is nothing to match: the query runs on what is loaded, so
    // the list keeps paging. The store stops it once a run of pages turns up nothing, which is what
    // keeps a query that matches nothing from walking the whole history.
    $effect(() => {
        if (requestList.ready && requestList.rows.length === 0 && !requestList.loading
            && !requestList.complete && !requestList.exhausted) {
            void loadOlderRequests()
        }
    })

    function clearFilter() {
        currentQuery = neutralQuery()
    }
</script>

<!-- Title, filter and table header stay put; only the rows scroll, inside the list itself. -->
<Page class="overflow-hidden">
    <PageHead>
        <PageTitle>{$_("userspace.requests.title")}</PageTitle>
    </PageHead>

    <PageContent class="min-h-0 overflow-hidden">
        <RequestFilterComponent bind:requestQuery={currentQuery} class="mb-2 shrink-0" />

        {#if requestList.rows.length > 0}
            <RequestList
                    class="flex-1"
                    rows={requestList.rows}
                    onEndReached={loadOlderRequests}
                    onRowClick={(request) => goto(`/requests/${request.request_id}`)}
            />

            {#if requestList.exhausted && !requestList.complete}
                <div class="flex shrink-0 justify-center pt-2">
                    <Button variant="outline" size="sm" onclick={searchFurtherBack}>
                        <MagnifyingGlassIcon />
                        {$_("userspace.requests.search_further")}
                    </Button>
                </div>
            {/if}
        {:else if !requestList.ready || requestList.loading}
            <ContentLoading />
        {:else if requestList.loaded === 0}
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
                        {$_("userspace.requests.empty.filtered.description", {values: {count: requestList.loaded}})}
                    </EmptyDescription>
                </EmptyHeader>

                <EmptyContent>
                    <div class="flex flex-row gap-2">
                        <Button variant="outline" onclick={clearFilter}>
                            <FunnelXIcon />
                            {$_("userspace.requests.empty.filtered.clear")}
                        </Button>

                        {#if !requestList.complete}
                            <Button variant="outline" onclick={searchFurtherBack}>
                                <MagnifyingGlassIcon />
                                {$_("userspace.requests.search_further")}
                            </Button>
                        {/if}
                    </div>
                </EmptyContent>
            </Empty>
        {/if}
    </PageContent>
</Page>
