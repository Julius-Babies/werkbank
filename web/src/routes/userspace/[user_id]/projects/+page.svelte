<script lang="ts">
    import {title} from "../state.ts";
    import {Button} from "$lib/components/ui/button";
    import {PlusIcon} from "@lucide/svelte";
    import {_} from 'svelte-i18n'
    import {getProjects, type Project} from "./getProjects.ts";
    import {onMount} from "svelte";
    import {getCoreRowModel, type RowSelectionState} from "@tanstack/table-core";
    import {createSvelteTable, FlexRender} from "$lib/components/ui/data-table";
    import {columns} from "./columns.ts";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import {Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {ArrowBendDownRightIcon, FolderSimpleDashedIcon, TrashIcon} from "phosphor-svelte";
    import NewProjectPopover from "./NewProjectPopover.svelte";
    import AccessStateDialog from "./access_state/AccessStateDialog.svelte";
    import ContentLoading from "../_lib/appshell/page/ContentLoading.svelte";
    import Page from "../_lib/appshell/page/Page.svelte";
    import PageHead from "../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../_lib/appshell/page/PageTitle.svelte";
    import DataTable from "../_lib/appshell/page/DataTable.svelte";
    import PageContent from "../_lib/appshell/page/PageContent.svelte";
    import { fly, slide } from "svelte/transition";

    $effect(() => {
        title.set($_("userspace.projects.title"))
    })

    let projects: Project[] | "loading" = $state("loading");

    onMount(() => {
        getProjects()
            .then(result => projects = result)
    })

    function reloadProjects() {
        getProjects().then(result => projects = result)
    }

    let accessSettingsForProject: string | null = $state(null);

    let rowSelection: RowSelectionState = $state({});
    const table = $derived(projects === "loading" ? null : createSvelteTable({
        get data() {
            return projects as Project[];
        },
        columns: columns({
            onReloadProjects: reloadProjects,
            onOpenAccessSettingsForProject: (projectId) => {
                accessSettingsForProject = projectId;
            }
        }),
        getCoreRowModel: getCoreRowModel(),
        getRowId: (row: Project) => row.project_id,
        enableRowSelection: true,
        state: {
            get rowSelection() {
                return rowSelection;
            },
        },
        onRowSelectionChange: (updater) => {
            rowSelection =
                typeof updater === "function" ? updater(rowSelection) : updater;
        },
    }));
</script>

<Page>
    <PageHead>
        <PageTitle>{$_("userspace.projects.title")}</PageTitle>
        <NewProjectPopover>
            <Button
            >
                <PlusIcon />
                {$_("userspace.projects.your-projects.create")}
            </Button>
        </NewProjectPopover>
    </PageHead>

    <PageContent>
        {#if projects === "loading" || !table}
            <ContentLoading />
        {:else}
            <DataTable {table}>
                {#snippet empty()}
                    <Empty>
                        <EmptyHeader>
                            <EmptyMedia variant="icon">
                                <FolderSimpleDashedIcon />
                            </EmptyMedia>
                            <EmptyTitle>{$_("userspace.projects.your-projects.list.empty.title")}</EmptyTitle>
                            <EmptyDescription>{$_("userspace.projects.your-projects.list.empty.description")}</EmptyDescription>
                        </EmptyHeader>

                        <EmptyContent>
                            <div class="flex flex-row gap-2">
                                <NewProjectPopover>
                                    <Button>
                                        <ArrowBendDownRightIcon />
                                        {$_("userspace.projects.your-projects.list.empty.create")}
                                    </Button>
                                </NewProjectPopover>
                            </div>
                        </EmptyContent>
                    </Empty>
                {/snippet}
            </DataTable>
        {/if}
    </PageContent>


    <div
            class="sticky bottom-0 left-0 flex flex-row justify-center w-full overflow-hidden pb-4"
    >
        {#if Object.entries(rowSelection).length > 0}
            {@const selectionCount = Object.entries(rowSelection).length}
            <div
                    class="flex flex-row items-center justify-center gap-2 h-12 w-fit rounded-full bg-background drop-shadow-xl border py-4 pl-4 pr-2"
                    transition:fly={{y: 100}}
            >
                <span class="font-sm font-medium tracking-tight text-accent-foreground">
                    {selectionCount} Projekt{#if selectionCount !== 1}e{/if} ausgewählt
                </span>

                <div class="w-px h-lh bg-muted rotate-12"></div>
                <div class="w-px h-lh bg-muted rotate-12"></div>

                <Button variant="destructive" size="icon">
                    <TrashIcon />
                </Button>
            </div>
        {/if}
    </div>
</Page>

{#if accessSettingsForProject}
    <AccessStateDialog
            onClose={(reload) => {accessSettingsForProject = null; if (reload) reloadProjects()}}
            projectId={accessSettingsForProject}
    />
{/if}
