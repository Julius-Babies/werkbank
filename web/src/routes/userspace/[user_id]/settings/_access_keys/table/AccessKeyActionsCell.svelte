<script lang="ts">
    import type {AccessKey} from "../getAccessKeys.ts";
    import {Button} from "$lib/components/ui/button";
    import {TrashIcon} from "phosphor-svelte";

    let {
        accessKey,
        onDelete,
    }: {
        accessKey: AccessKey,
        onDelete: (id: string) => void,
    } = $props();
</script>

<Button variant="ghost" size="icon-sm" onclick={() => onDelete(accessKey.id)}>
    <TrashIcon />
</Button>
