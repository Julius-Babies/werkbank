<script lang="ts">
    import {title} from "../state.ts";
    import {Button} from "$lib/components/ui/button";
    import {Loader, PlusIcon} from "@lucide/svelte";
    import {_} from 'svelte-i18n'
    import {getProjects, type Project} from "./getProjects.ts";
    import {onMount} from "svelte";
    import {getCoreRowModel, type RowSelectionState} from "@tanstack/table-core";
    import {createSvelteTable, FlexRender} from "$lib/components/ui/data-table";
    import {columns} from "./columns.ts";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import {Empty, EmptyContent, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {FolderSimpleDashedIcon, ArrowBendDownRightIcon} from "phosphor-svelte";
    import NewProjectPopover from "./NewProjectPopover.svelte";
    import {EmptyDescription} from "$lib/components/ui/empty";
    import AccessStateDialog from "./access_state/AccessStateDialog.svelte";

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

<div class="flex flex-col overflow-y-auto">
    <div class="flex flex-row justify-between w-full">
        <h1 class="text-2xl font-bold flex-1">{$_("userspace.projects.your-projects.title")}</h1>
        <NewProjectPopover>
            <Button
            >
                <PlusIcon />
                {$_("userspace.projects.your-projects.create")}
            </Button>
        </NewProjectPopover>
    </div>

    <div>
        {#if projects === "loading"}
            <div class="flex flex-row gap-2 items-center w-full px-4 py-6 justify-center">
                <div class="animate-spin aspect-square w-6 h-6"><Loader /></div>
                <span>{$_("userspace.projects.your-projects.list.loading")}</span>
            </div>
        {:else}
            <Table>
                <TableHeader>
                    {#each table?.getHeaderGroups() as headerGroup (headerGroup.id)}
                        <TableRow>
                            {#each headerGroup.headers as header (header.id)}
                                <TableHead
                                        colspan={header.colSpan}
                                        class={header.column.columnDef.meta?.compact ? "w-px whitespace-nowrap" : ""}
                                >
                                    {#if !header.isPlaceholder}
                                        <FlexRender
                                                content={header.column.columnDef.header}
                                                context={header.getContext()}
                                        />
                                    {/if}
                                </TableHead>
                            {/each}
                        </TableRow>
                    {/each}
                </TableHeader>

                <TableBody>
                    {#each table?.getRowModel().rows as row (row.id)}
                        <TableRow data-state={row.getIsSelected() && "selected"}>
                            {#each row.getVisibleCells() as cell (cell.id)}
                                <TableCell>
                                    <FlexRender
                                            content={cell.column.columnDef.cell}
                                            context={cell.getContext()}
                                    />
                                </TableCell>
                            {/each}
                        </TableRow>
                    {:else}
                        <TableRow>
                            <TableCell colspan={columns.length} class="h-24 text-center">
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
                            </TableCell>
                        </TableRow>
                    {/each}
                </TableBody>
            </Table>
        {/if}
    </div>
</div>

{#if accessSettingsForProject}
    <AccessStateDialog
            onClose={() => accessSettingsForProject = null}
            projectId={accessSettingsForProject}
    />
{/if}