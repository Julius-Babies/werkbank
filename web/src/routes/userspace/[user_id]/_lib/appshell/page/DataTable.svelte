<script lang="ts">
    import type {Table as TableType} from "@tanstack/table-core";
    import {FlexRender} from "$lib/components/ui/data-table";
    import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "$lib/components/ui/table";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import type {Snippet} from "svelte";

    let {
        table,
        empty,
        cellClass,
        onRowClick,
        ghostRows = 0,
        onGhostsEnter,
    }: {
        table: TableType<any>,
        empty?: Snippet,
        cellClass?: string,
        onRowClick?: (row: any) => void,
        /** Placeholder rows behind the rendered ones, standing in for rows that are not rendered yet. */
        ghostRows?: number,
        /** Called when the ghost rows scroll into view, so the caller can render more real rows. */
        onGhostsEnter?: () => void,
    } = $props();

    let columns = $derived(table?.getVisibleLeafColumns() ?? []);

    // The first ghost row is the trigger: once it comes into view the runway is being entered.
    let runway: HTMLTableRowElement | null = $state(null);

    $effect(() => {
        if (!runway || !onGhostsEnter) return;

        const observer = new IntersectionObserver(
            (entries) => {
                if (entries.some((entry) => entry.isIntersecting)) onGhostsEnter();
            },
            {rootMargin: "200px"},
        );

        observer.observe(runway);
        return () => observer.disconnect();
    });

    function widthClass(compact: boolean | undefined): string {
        return compact ? "w-px whitespace-nowrap" : "w-full max-w-0";
    }
</script>

{#snippet ghostCells()}
    {#each columns as column (column.id)}
        <TableCell class={widthClass(column.columnDef.meta?.compact) + " " + (cellClass || "")}>
            <Skeleton class={column.columnDef.meta?.compact ? "h-4 w-24" : "h-4 w-full"} />
        </TableCell>
    {/each}
{/snippet}

<Table>
    <TableHeader>
        {#each table?.getHeaderGroups() as headerGroup (headerGroup.id)}
            <TableRow>
                {#each headerGroup.headers as header (header.id)}
                    <TableHead
                            colspan={header.colSpan}
                            class={widthClass(header.column.columnDef.meta?.compact) + " font-heading"}
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
                            class={widthClass(cell.column.columnDef.meta?.compact) + " " + (cellClass || "")}
                    >
                        <FlexRender
                                content={cell.column.columnDef.cell}
                                context={cell.getContext()}
                        />
                    </TableCell>
                {/each}
            </TableRow>
        {:else}
            {#if empty && ghostRows === 0}
                <TableRow>
                    <TableCell colspan={table?._getColumnDefs().length} class="h-24 text-center">
                        {@render empty()}
                    </TableCell>
                </TableRow>
            {/if}
        {/each}

        {#if ghostRows > 0}
            <TableRow aria-hidden="true" bind:ref={runway}>{@render ghostCells()}</TableRow>
            {#each Array.from({length: ghostRows - 1}) as _, index (index)}
                <TableRow aria-hidden="true">{@render ghostCells()}</TableRow>
            {/each}
        {/if}
    </TableBody>
</Table>