<script lang="ts">
    import type {Snippet} from "svelte";

    let {
        children,
    }: {
        children?: Snippet,
    } = $props();
</script>

<div class="flex flex-row justify-between w-full px-4 pt-2">
    {@render children?.()}
</div>