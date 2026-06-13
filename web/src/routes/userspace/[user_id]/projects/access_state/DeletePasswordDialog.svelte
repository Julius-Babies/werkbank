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
    import {deletePasswordForProject} from "./deletePassword.ts";
    import { Loader } from "@lucide/svelte";
    import { slide } from "svelte/transition";

    let {
        projectId,
        passwordId,
        label,
        onClose,
    }: {
        projectId: string,
        passwordId: string,
        label: string,
        onClose: (success: boolean) => void
    } = $props();

    let isDeleting = $state(false);

    async function deletePassword() {
        if (isDeleting) return;
        isDeleting = true;

        try {
            const success = await deletePasswordForProject(projectId, passwordId)
            if (success) onClose(true);
        } finally {
            isDeleting = false;
        }
    }
</script>

<Dialog open={true} onOpenChange={open => { if (!open) onClose(false) }}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.delete_password.dialog.title", { values: { label } })}</DialogTitle>
            <DialogDescription>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.delete_password.dialog.description")}</DialogDescription>
        </DialogHeader>

        <DialogFooter>
            <Button variant="outline" onclick={() => onClose(false)}>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.delete_password.dialog.footer.cancel")}</Button>
            <Button variant="destructive" onclick={deletePassword}>
                {#if isDeleting}
                    <div class="aspect-square w-4 h-4 animate-spin" transition:slide={{ duration: 100, axis: "x" }}>
                        <Loader />
                    </div>
                {/if}
                {$_("userspace.projects.project.access_settings.dialog.project_access.passwords.delete_password.dialog.footer.delete")}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>