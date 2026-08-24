<script lang="ts">
    import type {Snippet} from "svelte";
    import {cn} from "$lib/utils.js";

    let {
        children,
        class: className,
    }: {
        children?: Snippet;
        class?: string;
    } = $props();
</script>

<div class={cn("flex min-h-0 flex-1 flex-col overflow-y-auto py-2 px-4", className)}>
    {@render children?.()}
</div>
