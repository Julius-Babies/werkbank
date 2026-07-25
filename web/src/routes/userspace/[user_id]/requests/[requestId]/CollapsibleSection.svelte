<script lang="ts">
    import {ChevronRight} from "@lucide/svelte";
    import type {Snippet} from "svelte";

    let {
        title,
        count,
        open = true,
        children,
    }: {
        title: string;
        count?: number;
        open?: boolean;
        children: Snippet;
    } = $props();
</script>

<details class="collapsible rounded-lg border border-gray-200 bg-white" {open}>
    <summary class="flex cursor-pointer list-none items-center gap-2 px-3 py-2 select-none">
        <ChevronRight size={15} class="chevron shrink-0 text-gray-400" />
        <span class="font-heading text-xs font-semibold tracking-wide text-gray-500 uppercase">{title}</span>
        {#if count !== undefined}
            <span class="ml-auto rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500">{count}</span>
        {/if}
    </summary>
    <div class="border-t border-gray-100 px-3 py-1">
        {@render children()}
    </div>
</details>

<style>
    .collapsible :global(.chevron) {
        transition: transform 150ms ease;
    }

    .collapsible[open] :global(.chevron) {
        transform: rotate(90deg);
    }

    summary::-webkit-details-marker {
        display: none;
    }
</style>
