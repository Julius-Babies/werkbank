<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import NewAccessKeyDialog from "./NewAccessKeyDialog.svelte";
    import DeleteAccessKeyDialog from "./DeleteAccessKeyDialog.svelte";
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {PlusIcon, Loader, Trash2Icon} from "@lucide/svelte";
    import {getAccessKeys, type AccessKey} from "./getAccessKeys.ts";
    import {getCoreRowModel, type RowSelectionState} from "@tanstack/table-core";
    import {createSvelteTable, FlexRender} from "$lib/components/ui/data-table";
    import {columns} from "./columns.ts";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import {Empty, EmptyContent, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import {KeyholeIcon} from "phosphor-svelte";

    let accessKeys: AccessKey[] | "loading" = $state("loading");

    async function loadAccessKeys() {
        accessKeys = await getAccessKeys();
    }

    onMount(() => {
        loadAccessKeys();
    })

    let showNewAccessKeyDialog = $state(false);

    let deleteTarget: { ids: string[]; label?: string } | null = $state(null);

    let rowSelection: RowSelectionState = $state({});

    const table = $derived(accessKeys === "loading" ? null : createSvelteTable({
        get data() {
            return accessKeys as AccessKey[];
        },
        columns: columns({
            onDeleteKey: (id) => {
                const key = (accessKeys as AccessKey[]).find(k => k.id === id);
                deleteTarget = { ids: [id], label: key?.name };
            },
            nameHeader: $_("userspace.settings.access_keys.table.name"),
            createdAtHeader: $_("userspace.settings.access_keys.table.created_at"),
        }),
        getCoreRowModel: getCoreRowModel(),
        getRowId: (row: AccessKey) => row.id,
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

    let selectedCount = $derived(Object.keys(rowSelection).length);
</script>

<div class="flex flex-col gap-2">
    <div>
        <h3 class="text-xl font-bold flex-1 mt-2">{$_("userspace.settings.access_keys.title")}</h3>
        <p class="text-sm text-gray-500">{$_("userspace.settings.access_keys.description")}</p>
    </div>

    <div class="flex flex-row gap-2">
        <Button class="w-fit" onclick={() => showNewAccessKeyDialog = true}>
            <PlusIcon />
            {$_("userspace.settings.access_keys.create")}
        </Button>

        {#if selectedCount > 0}
            <Button variant="destructive" class="w-fit" onclick={() => deleteTarget = { ids: Object.keys(rowSelection) }}>
                <Trash2Icon />
                {$_("userspace.settings.access_keys.delete_selected", { values: { count: selectedCount } })}
            </Button>
        {/if}
    </div>

    {#if accessKeys === "loading"}
        <div class="flex flex-row gap-2 items-center w-full px-4 py-6 justify-center">
            <div class="animate-spin aspect-square w-6 h-6"><Loader /></div>
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
                        <TableCell colspan={table?._getColumnDefs().length} class="h-24 text-center">
                            <Empty>
                                <EmptyHeader>
                                    <EmptyMedia variant="icon">
                                        <KeyholeIcon />
                                    </EmptyMedia>
                                    <EmptyTitle>{$_("userspace.settings.access_keys.empty.title")}</EmptyTitle>
                                </EmptyHeader>

                                <EmptyContent>
                                    <Button class="w-fit" onclick={() => showNewAccessKeyDialog = true}>
                                        <PlusIcon />
                                        {$_("userspace.settings.access_keys.create")}
                                    </Button>
                                </EmptyContent>
                            </Empty>
                        </TableCell>
                    </TableRow>
                {/each}
            </TableBody>
        </Table>
    {/if}
</div>

{#if showNewAccessKeyDialog}
    <NewAccessKeyDialog
            onclose={reload => { showNewAccessKeyDialog = false; if(reload) loadAccessKeys(); }}
    />
{/if}

{#if deleteTarget}
    <DeleteAccessKeyDialog
            ids={deleteTarget.ids}
            label={deleteTarget.label}
            onClose={success => { deleteTarget = null; if(success) { loadAccessKeys(); rowSelection = {}; } }}
    />
{/if}
