<script lang="ts">
    import type {Snippet} from "svelte";

    let {
        children,
    }: {
        children?: Snippet;
    } = $props();
</script>

<div class="flex flex-1 flex-col overflow-y-auto py-2 px-4">
    {@render children?.()}
</div>
