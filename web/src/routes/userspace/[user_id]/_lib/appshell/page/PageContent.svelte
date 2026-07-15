<script lang="ts">
    import type {Snippet} from "svelte";

    let {
        children,
    }: {
        children?: Snippet;
    } = $props();
</script>

<div class="flex flex-col overflow-y-auto py-2 px-4">
    {@render children?.()}
</div>