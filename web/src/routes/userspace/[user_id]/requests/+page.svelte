<script lang="ts">
    import {type RequestUpdate, title} from "../state.ts";
    import {onMount} from "svelte";
    import {goto} from "$app/navigation";
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
    import {ArrowBendDownRightIcon, ListDashesIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import PageContent from "../_lib/appshell/page/PageContent.svelte";

    $effect(() => {
        title.set($_("userspace.requests.title"))
    })

    let isLoading = $state(true)

    onMount(() => {
        fetchRequests()
            .then(() => isLoading = false);
    })

    let table = createSvelteTable({
        get data() {
            return $requests
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
            <DataTable
                    {table}
                    cellClass="py-1.5"
                    onRowClick={(request: RequestUpdate) => goto(`/requests/${request.request_id}`)}
            >
                {#snippet empty()}
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
                {/snippet}
            </DataTable>
        {/if}
    </PageContent>
</Page>
