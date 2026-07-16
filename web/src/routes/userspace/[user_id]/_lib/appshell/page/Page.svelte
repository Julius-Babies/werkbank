<script lang="ts">
    import type {Snippet} from "svelte";

    let {
        children,
    }: {
        children?: Snippet;
    } = $props();
</script>

<div class="flex flex-col overflow-y-auto pt-2 h-full">
    {@render children?.()}
</div>
