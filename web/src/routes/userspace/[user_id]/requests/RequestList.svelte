<script lang="ts">
    import {_} from "svelte-i18n";
    import {createVirtualizer} from "$lib/components/ui/virtual";
    import type {RequestRow} from "./requests.svelte.ts";
    import RequestListRow from "./RequestListRow.svelte";

    let {
        rows,
        onEndReached,
        onRowClick,
        class: className,
    }: {
        rows: RequestRow[],
        /** Called while the end of the list comes into reach, so the caller can load more. */
        onEndReached?: () => void,
        onRowClick?: (request: RequestRow["request"]) => void,
        class?: string,
    } = $props();

    /** Rows are single line and identically padded, so their height is known without measuring. */
    const ROW_HEIGHT = 33;

    /**
     * The header scrolls with the rows and is only pinned by `sticky`, so it shares their width no
     * matter whether a scrollbar is showing. The virtualizer keeps its space free as leading padding.
     */
    const HEADER_HEIGHT = 37;

    /** How many rows before the end asking for the next page starts. */
    const LOAD_AHEAD = 40;

    let viewport: HTMLDivElement | null = $state(null);

    const virtualizer = createVirtualizer<HTMLDivElement, HTMLDivElement>({
        get count() {
            return rows.length;
        },
        getScrollElement: () => viewport,
        estimateSize: () => ROW_HEIGHT,
        paddingStart: HEADER_HEIGHT,
        overscan: 12,
        getItemKey: (index) => rows[index]?.id ?? index,
    });

    // Both a scroll into the last rows and a list too short to scroll at all have to ask for more,
    // otherwise a filter that matches little would stop loading at the first page.
    $effect(() => {
        const last = virtualizer.items.at(-1);
        if (last === undefined || last.index >= rows.length - LOAD_AHEAD) onEndReached?.();
    });
</script>

<div
        bind:this={viewport}
        role="table"
        aria-label={$_("userspace.requests.title")}
        aria-rowcount={rows.length}
        class={"min-h-0 overflow-y-auto " + (className ?? "")}
>
    <div class="relative w-full" style="height: {virtualizer.totalSize}px">
        <div
                role="row"
                class="sticky top-0 z-10 box-border flex flex-row border-b bg-background font-heading text-sm text-foreground"
                style="height: {HEADER_HEIGHT}px"
        >
            <div role="columnheader" class="w-56 shrink-0 px-3 py-2">{$_("userspace.requests.table.project")}</div>
            <div role="columnheader" class="min-w-0 flex-1 px-3 py-2">{$_("userspace.requests.table.resource")}</div>
            <div role="columnheader" class="w-28 shrink-0 px-3 py-2">{$_("userspace.requests.table.result")}</div>
        </div>

        {#each virtualizer.items as item (item.key)}
            {@const row = rows[item.index]}
            {#if row}
                <RequestListRow
                        {row}
                        index={item.index}
                        style="height: {item.size}px; transform: translateY({item.start}px)"
                        onclick={onRowClick}
                />
            {/if}
        {/each}
    </div>
</div>
