<script lang="ts">
    import {
        Dialog,
        DialogContent,
        DialogDescription,
        DialogFooter,
        DialogHeader,
        DialogTitle
    } from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {_} from "svelte-i18n";
    import {Loader} from "@lucide/svelte";
    import {slide} from "svelte/transition";
    import {deleteAccessKey} from "./deleteAccessKey.ts";

    let {
        ids,
        label,
        onClose,
    }: {
        ids: string[];
        label?: string;
        onClose: (success: boolean) => void
    } = $props();

    let isDeleting = $state(false);

    async function handleDelete() {
        if (isDeleting) return;
        isDeleting = true;

        try {
            const results = await Promise.all(ids.map(id => deleteAccessKey(id)));
            const allSucceeded = results.every(r => r);
            if (allSucceeded) onClose(true);
        } finally {
            isDeleting = false;
        }
    }
</script>

<Dialog open={true} onOpenChange={open => { if (!open) onClose(false) }}>
    <DialogContent>
        <DialogHeader>
            {#if ids.length === 1}
                <DialogTitle>{$_("userspace.settings.access_keys.delete_dialog.title.single", { values: { label } })}</DialogTitle>
            {:else}
                <DialogTitle>{$_("userspace.settings.access_keys.delete_dialog.title.multiple", { values: { count: ids.length } })}</DialogTitle>
            {/if}
            <DialogDescription>{$_("userspace.settings.access_keys.delete_dialog.description")}</DialogDescription>
        </DialogHeader>

        <DialogFooter>
            <Button variant="outline" onclick={() => onClose(false)}>{$_("userspace.settings.access_keys.delete_dialog.footer.cancel")}</Button>
            <Button variant="destructive" onclick={handleDelete} disabled={isDeleting}>
                {#if isDeleting}
                    <div class="aspect-square w-4 h-4 animate-spin" transition:slide={{ duration: 100, axis: "x" }}>
                        <Loader />
                    </div>
                {/if}
                {$_("userspace.settings.access_keys.delete_dialog.footer.delete")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>
