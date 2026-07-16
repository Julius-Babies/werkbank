<script lang="ts">
    import type {Table as TableType} from "@tanstack/table-core";
    import {FlexRender} from "$lib/components/ui/data-table";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import type {Snippet} from "svelte";

    let {
        table,
        empty,
        cellClass,
        onRowClick,
    }: {
        table: TableType<any>,
        empty?: Snippet,
        cellClass?: string,
        onRowClick?: (row: any) => void,
    } = $props();
</script>

<Table>
    <TableHeader>
        {#each table?.getHeaderGroups() as headerGroup (headerGroup.id)}
            <TableRow>
                {#each headerGroup.headers as header (header.id)}
                    <TableHead
                            colspan={header.colSpan}
                            class={header.column.columnDef.meta?.compact ? "w-px whitespace-nowrap" : "w-full max-w-0"}
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
            <TableRow
                    data-state={row.getIsSelected() && "selected"}
                    onclick={onRowClick ? () => onRowClick(row.original) : undefined}
                    class={onRowClick ? "cursor-pointer" : ""}
            >
                {#each row.getVisibleCells() as cell (cell.id)}
                    <TableCell
                            class={(cell.column.columnDef.meta?.compact ? "w-px whitespace-nowrap" : "w-full max-w-0") + " " + (cellClass || "")}
                    >
                        <FlexRender
                                content={cell.column.columnDef.cell}
                                context={cell.getContext()}
                        />
                    </TableCell>
                {/each}
            </TableRow>
        {:else}
            {#if empty}
                <TableRow>
                    <TableCell colspan={table?._getColumnDefs().length} class="h-24 text-center">
                        {@render empty()}
                    </TableCell>
                </TableRow>
            {/if}
        {/each}
    </TableBody>
</Table>