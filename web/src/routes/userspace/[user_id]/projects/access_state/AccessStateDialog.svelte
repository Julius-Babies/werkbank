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
    import {ButtonGroup} from "$lib/components/ui/button-group";
    import {PasswordIcon, PlusIcon, TrashIcon} from "phosphor-svelte";
    import {Avatar, AvatarFallback, AvatarImage} from "$lib/components/ui/avatar";
    import {_} from "svelte-i18n";
    import {getProjectAccessState, type ProjectAccessState} from "./state.ts";
    import {Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle} from "$lib/components/ui/empty";
    import NewPasswordDialog from "./NewPasswordDialog.svelte";
    import PasswordItem from "./PasswordItem.svelte";

    let {
        projectId,
        onClose,
    }: {
        projectId: string,
        onClose: () => void,
    } = $props();

    let accessState: ProjectAccessState | null = $state(null);

    $effect(() => {
        if (projectId) reload()
    })

    function reload() {
        getProjectAccessState(projectId)
            .then(result => accessState = result)
    }

    let showNewPasswordDialog = $state(false);
</script>

{#if accessState}
    <Dialog open={true} onOpenChange={open => { if (!open) onClose() }}>
        <DialogContent class="flex flex-col max-h-11/12 h-full overflow-hidden blur-fade">
            <DialogHeader>
                <DialogTitle>
                    {$_("userspace.projects.project.access_settings.dialog.title", {values: {project: accessState.project_name}})}
                </DialogTitle>

                <DialogDescription>
                    {$_("userspace.projects.project.access_settings.dialog.description")}
                </DialogDescription>
            </DialogHeader>

            <div class="flex flex-col overflow-y-auto h-full gap-2 no-scrollbar">
                <h1 class="text-2xl">{$_("userspace.projects.project.access_settings.dialog.project_access.title")}</h1>

                <ButtonGroup class="flex flex-row w-full">
                    <Button variant={accessState.project_access_state === "disabled" ? "default" : "outline"}
                            class="flex flex-1">{$_("userspace.projects.project.access_settings.dialog.project_access.disabled.title")}</Button>
                    <Button variant={accessState.project_access_state === "restricted" ? "default" : "outline"}
                            class="flex flex-1">{$_("userspace.projects.project.access_settings.dialog.project_access.restricted.title")}</Button>
                    <Button variant={accessState.project_access_state === "open" ? "default" : "outline"}
                            class="flex flex-1">{$_("userspace.projects.project.access_settings.dialog.project_access.open.title")}</Button>
                </ButtonGroup>

                <div>
                    {#if accessState.project_access_state === "disabled"}
                        {$_("userspace.projects.project.access_settings.dialog.project_access.disabled.description")}
                    {:else if accessState.project_access_state === "restricted"}
                        {$_("userspace.projects.project.access_settings.dialog.project_access.restricted.description")}
                    {:else if accessState.project_access_state === "open"}
                        {$_("userspace.projects.project.access_settings.dialog.project_access.open.description")}
                    {/if}
                </div>

                <h2 class="text-xl mt-2">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.title")}</h2>
                <div>
                    <div class="border rounded-t-lg border-neutral-300 text-neutral-800">
                        {#if accessState.project_passwords.length > 0}
                            {#each accessState.project_passwords as password (password.id)}
                                <PasswordItem
                                        projectId={projectId}
                                        passwordId={password.id}
                                        label={password.label}
                                        createdAt={password.created_at}
                                        onReload={() => reload()}
                                />
                            {/each}
                        {:else}
                            <Empty>
                                <EmptyHeader>
                                    <EmptyMedia variant="icon">
                                        <PasswordIcon/>
                                    </EmptyMedia>
                                    <EmptyTitle>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.no_passwords.title")}</EmptyTitle>
                                    <EmptyDescription>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.no_passwords.description")}</EmptyDescription>
                                </EmptyHeader>
                            </Empty>
                        {/if}
                    </div>
                    <button
                            class="border-b-2 border-l-2 border-r-2 border-dashed rounded-b-lg border-neutral-300 text-foreground p-2 flex flex-row items-center justify-center gap-2 cursor-pointer transition-colors duration-100 hover:bg-neutral-100 select-none w-full"
                            onclick={() => showNewPasswordDialog = true}
                    >
                        <PlusIcon/>
                        <span class="font-medium">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.button")}</span>
                    </button>
                </div>

                <h2 class="text-xl mt-2">Invited Users</h2>
                <div>
                    <div class="border rounded-t-lg border-neutral-300 text-neutral-800">
                        <div class="border-b flex flex-row items-center justify-between p-2 gap-2 overflow-hidden">
                            <div class="flex flex-row items-center gap-2 pl-1">
                                <Avatar class="size-8 rounded-lg">
                                    <AvatarImage src={"https://avatars.githubusercontent.com/u/66371497?v=4"}
                                                 alt={"Julius Babies"}/>
                                    <AvatarFallback class="rounded-lg">JB</AvatarFallback>
                                </Avatar>
                                <div class="flex flex-col items-start">
                                    <span>Julius-Babies</span>
                                    <span class="text-xs text-neutral-600 font-medium">Your profile always has permission</span>
                                </div>
                            </div>
                            <Button variant="destructive" size="icon" disabled>
                                <TrashIcon/>
                            </Button>
                        </div>

                        <div class="flex flex-row items-center justify-between p-2 gap-2 overflow-hidden">
                            <div class="flex flex-row items-center gap-2 pl-1">
                                <Avatar class="size-8 rounded-lg">
                                    <AvatarImage src={"https://thispersondoesnotexist.com"} alt={"Julius Babies"}/>
                                    <AvatarFallback class="rounded-lg">JB</AvatarFallback>
                                </Avatar>
                                <div class="flex flex-col items-start">
                                    <span>Max-Mustermann</span>
                                    <span class="text-xs text-neutral-600 font-medium">5 days ago</span>
                                </div>
                            </div>
                            <Button variant="destructive" size="icon">
                                <TrashIcon/>
                            </Button>
                        </div>
                    </div>
                    <div class="border-b-2 border-l-2 border-r-2 border-dashed rounded-b-lg border-neutral-300 text-foreground p-2 flex flex-row items-center justify-center gap-2 cursor-pointer transition-colors duration-100 hover:bg-neutral-100 select-none">
                        <PlusIcon/>
                        <span class="font-medium">Invite someone new</span>
                    </div>
                </div>
            </div>

            <DialogFooter>
                <Button variant="outline">Cancel</Button>
                <Button>Save</Button>
            </DialogFooter>
        </DialogContent>
    </Dialog>

    {#if showNewPasswordDialog}
        <NewPasswordDialog
                projectId={projectId}
                onClose={(shouldReload) => { showNewPasswordDialog = false; if (shouldReload) reload() }}
        />
    {/if}
{/if}