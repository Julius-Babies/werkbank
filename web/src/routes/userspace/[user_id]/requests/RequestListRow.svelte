<script lang="ts">
    import type {RequestRow} from "./requests.svelte.ts";
    import DataTableProjectCell from "./table/DataTableProjectCell.svelte";
    import DataTableUrlCell from "./table/DataTableUrlCell.svelte";
    import DataTableResultCell from "./table/DataTableResultCell.svelte";

    let {
        row,
        index,
        style,
        onclick,
    }: {
        row: RequestRow,
        index: number,
        style?: string,
        onclick?: (request: RequestRow["request"]) => void,
    } = $props();

    // Reading the request through the row keeps this component subscribed to its own live updates
    // only, so an update never re-renders anything but the row it belongs to.
    let request = $derived(row.request);
</script>

<div
        role="row"
        tabindex="-1"
        aria-rowindex={index + 1}
        {style}
        class="absolute top-0 left-0 flex w-full cursor-pointer flex-row items-center overflow-hidden border-b transition-colors hover:bg-muted/50"
        onclick={() => onclick?.(request)}
        onkeydown={(event) => {
            if (event.key === "Enter" || event.key === " ") onclick?.(request)
        }}
>
    <div role="cell" class="w-56 shrink-0 truncate px-3 py-1.5">
        <DataTableProjectCell {request} />
    </div>
    <div role="cell" class="min-w-0 flex-1 px-3 py-1.5">
        <DataTableUrlCell {request} />
    </div>
    <div role="cell" class="w-28 shrink-0 px-3 py-1.5">
        <DataTableResultCell {request} />
    </div>
</div>
