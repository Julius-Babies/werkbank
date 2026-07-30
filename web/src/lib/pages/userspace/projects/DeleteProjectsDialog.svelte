<script lang="ts">
    import { Button } from "$lib/components/ui/button";
    import {DialogHeader, Dialog, DialogTitle, DialogFooter, DialogContent} from "$lib/components/ui/dialog";
    import DialogDescription from "$lib/components/ui/dialog/dialog-description.svelte";
    import { Loader } from "@lucide/svelte";
    import type { Project } from "../../../../routes/userspace/[user_id]/projects/getProjects";
    import AvatarGroup from "$lib/components/ui/avatar/avatar-group.svelte";
    import { Avatar, AvatarImage } from "$lib/components/ui/avatar";
    import { ProjectRepository } from "$lib/api/ProjectRepository.ts";
    import { _ } from "svelte-i18n";

    let {
        projects,
        onclose
    } : {
        projects: Project[],
        onclose: (deletedAnyProject: boolean) => void,
    } = $props();

    let open = $state(true);
    let isDeleting = $state(false);
    let deletedAnyProject = $state(false);

    // Deleted projects drop out so a retry after a partial failure does not run into their 404s
    let pendingProjects = $state(projects);

    async function handleDelete() {
        if (isDeleting) return
        isDeleting = true;

        try {
            const results = await Promise.all(
                pendingProjects.map(project => ProjectRepository.deleteProject(project.project_id))
            );

            deletedAnyProject = deletedAnyProject || results.some(success => success);
            pendingProjects = pendingProjects.filter((_project, index) => !results[index]);

            // On a partial failure the dialog stays open so the failure remains visible
            if (pendingProjects.length === 0) open = false;
        } finally {
            isDeleting = false;
        }
    }
</script>

<Dialog bind:open={open} onOpenChangeComplete={isOpen => {if (!isOpen) onclose(deletedAnyProject)}}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>
                <AvatarGroup class="mb-2">
                    {#each projects as project (project.project_id)}
                        <Avatar class="bg-background size-6">
                            <AvatarImage src="/api/projects/{project.project_id}/icon" alt={project.project_name}></AvatarImage>
                        </Avatar>
                    {/each}
                </AvatarGroup>
                {#if projects.length === 1}
                    {$_("userspace.projects.delete_dialog.title.single")}
                {:else}
                    {$_("userspace.projects.delete_dialog.title.multiple")}
                {/if}
            </DialogTitle>
            <DialogDescription>
                {$_("userspace.projects.delete_dialog.description")}
            </DialogDescription>

            <DialogFooter>
                <Button variant="outline" onclick={() => open = false}>
                    {$_("userspace.projects.delete_dialog.footer.cancel")}
                </Button>
                <Button variant="destructive" onclick={handleDelete} disabled={isDeleting}>
                    {#if isDeleting}
                        <Loader class="animate-spin" />
                    {/if}
                    {$_("userspace.projects.delete_dialog.footer.delete")}
                </Button>
            </DialogFooter>
        </DialogHeader>
    </DialogContent>
</Dialog>
