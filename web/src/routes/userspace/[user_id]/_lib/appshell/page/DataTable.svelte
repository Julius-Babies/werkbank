<script lang="ts">
    import type {Table as TableType} from "@tanstack/table-core";
    import {FlexRender} from "$lib/components/ui/data-table";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import type {Snippet} from "svelte";

    let {
        table,
        empty,
    }: {
        table: TableType<any>,
        empty?: Snippet
    } = $props();
</script>

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
                    <TableCell
                            class={cell.column.columnDef.meta?.compact ? "w-px whitespace-nowrap" : ""}
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