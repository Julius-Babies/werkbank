<script lang="ts">
    import {CaretDownIcon, CheckCircleIcon, ProhibitInsetIcon, ShieldIcon} from "phosphor-svelte";
    import type {Project} from "../../getProjects.ts";
    import {_} from "svelte-i18n";
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuGroup,
        DropdownMenuGroupHeading,
        DropdownMenuItem,
        DropdownMenuTrigger
    } from "$lib/components/ui/dropdown-menu";
    import AccessStateOption from "./AccessStateOption.svelte";
    import {setAccessState} from "./changeAccessState.ts";

    let {
        project,
        onReloadProjects,
        onOpenAccessSettingsForProject,
    }: {
        project: Project,
        onReloadProjects: () => void,
        onOpenAccessSettingsForProject: (projectId: string) => void,
    } = $props();

    const DISABLED_TEXT_COLOR = "text-red-900";
    const RESTRICTED_TEXT_COLOR = "text-sky-800";
    const OPEN_TEXT_COLOR = "text-green-900";

    let colors = $derived.by(() => {
        switch (project.access_state) {
            case "disabled":
                return `bg-red-100 ${DISABLED_TEXT_COLOR} hover:bg-red-200`;
            case "restricted":
                return `bg-sky-100 ${RESTRICTED_TEXT_COLOR} hover:bg-sky-200`;
            case "open":
                return `bg-green-100 ${OPEN_TEXT_COLOR} hover:bg-green-200`;
        }
    })

    let loadingOption = $state<string | null>(null)
    let isContextMenuOpen = $state(false)

    async function changeAccessState(accessState: string) {
        if (loadingOption) return
        loadingOption = accessState
        try {
            const result = await setAccessState(
                project.project_id,
                accessState,
            )
            if (result) {
                isContextMenuOpen = false
                onReloadProjects()
            }
        } finally {
            loadingOption = null
        }
    }

    function handleOpenSettings() {
        isContextMenuOpen = false
        onOpenAccessSettingsForProject(project.project_id)
    }
</script>

<DropdownMenu bind:open={isContextMenuOpen}>
    <DropdownMenuTrigger>
        <button class={"px-1 py-0.5 rounded-md flex flex-row gap-1 font-mono text-xs items-center cursor-pointer transition-colors duration-150 " + colors}>
            {#if project.access_state === "disabled"}
                <ProhibitInsetIcon weight="fill" size="14"/>
                <span>{$_("userspace.projects.table.access_state.disabled")}</span>
            {:else if project.access_state === "restricted"}
                <ShieldIcon weight="fill" size="14"/>
                <span>{$_("userspace.projects.table.access_state.restricted")}</span>
            {:else if project.access_state === "open"}
                <CheckCircleIcon weight="fill" size="14"/>
                <span>{$_("userspace.projects.table.access_state.open")}</span>
            {/if}
            <CaretDownIcon weight="fill" class="ml-1" size="12"/>
        </button>
    </DropdownMenuTrigger>
    <DropdownMenuContent class="w-72" align="start">
        <DropdownMenuGroup>
            <DropdownMenuGroupHeading>{$_("userspace.projects.table.access_state.change.title")}</DropdownMenuGroupHeading>

            <AccessStateOption
                    icon={ProhibitInsetIcon}
                    isLoading={loadingOption === "disabled"}
                    title={$_("userspace.projects.table.access_state.disabled")}
                    description={$_("userspace.projects.table.access_state.disabled.description")}
                    textColor={DISABLED_TEXT_COLOR}
                    check={project.access_state === "disabled"}
                    onClick={() => changeAccessState("disabled")}
            />

            <AccessStateOption
                    icon={ShieldIcon}
                    isLoading={loadingOption === "restricted"}
                    title={$_("userspace.projects.table.access_state.restricted")}
                    description={$_("userspace.projects.table.access_state.restricted.description")}
                    textColor={RESTRICTED_TEXT_COLOR}
                    check={project.access_state === "restricted"}
                    onClick={() => changeAccessState("restricted")}
            />

            <AccessStateOption
                    icon={CheckCircleIcon}
                    isLoading={loadingOption === "open"}
                    title={$_("userspace.projects.table.access_state.open")}
                    description={$_("userspace.projects.table.access_state.open.description")}
                    textColor={OPEN_TEXT_COLOR}
                    check={project.access_state === "open"}
                    onClick={() => changeAccessState("open")}
            />
        </DropdownMenuGroup>

        <DropdownMenuGroup>
            <DropdownMenuGroupHeading>{$_("userspace.projects.table.access_state.change.more")}</DropdownMenuGroupHeading>
            <DropdownMenuItem onclick={handleOpenSettings}>
                <div class="flex flex-col w-full">
                    <span class="font-normal">
                            {$_("userspace.projects.table.access_state.change.custom")}
                    </span>
                    <span class="text-xs text-gray-500">
                        {$_("userspace.projects.table.access_state.change.custom.description")}
                    </span>
                </div>
            </DropdownMenuItem>
        </DropdownMenuGroup>
    </DropdownMenuContent>
</DropdownMenu>
