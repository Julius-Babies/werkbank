<script lang="ts">
    import {methodColors} from "$lib/components/requests/colors";
    import {CaretDownIcon, FunnelIcon, FunnelXIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {Avatar, AvatarFallback, AvatarGroup, AvatarGroupCount, AvatarImage} from "$lib/components/ui/avatar";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {Tooltip, TooltipContent, TooltipTrigger} from "$lib/components/ui/tooltip";
    import {_} from "svelte-i18n";
    import {FILTER_METHODS, isNeutralQuery, neutralQuery, queryFromFilter, type RequestQuery} from "./filter.ts";
    import RequestQueryInput from "./RequestQueryInput.svelte";
    import {loadProjects, projects} from "./projects.ts";
    import {cn} from "$lib/utils";

    let {
        requestQuery = $bindable(),
        class: className,
    }: {
        requestQuery: RequestQuery,
        class?: string
    } = $props()

    let isNeutral = $derived(isNeutralQuery(requestQuery))
    /** Icons shown in the chip before it turns into a "+n". */
    const MAX_CHIP_ICONS = 3

    let selectedProjects = $derived(requestQuery.filter.filter_projects)

    // The chip shows the icons of the selected projects, so they are needed before the list is opened.
    $effect(() => {
        if (selectedProjects.length > 0) loadProjects()
    })

    // A key without a project is either not loaded yet or belongs to a deleted project; both fall
    // back to the initials of the key.
    let chipIcons = $derived(selectedProjects.slice(0, MAX_CHIP_ICONS).map((projectKey) => ({
        projectKey,
        project: $projects?.find((project) => project.project_key === projectKey),
    })))
    let hiddenIcons = $derived(Math.max(0, selectedProjects.length - MAX_CHIP_ICONS))

    function toggleMethod(method: string) {
        const methods = requestQuery.filter.filter_methods
        requestQuery = queryFromFilter({
            ...requestQuery.filter,
            filter_methods: methods.includes(method)
                ? methods.filter(m => m !== method)
                : [...methods, method],
        })
    }

    function toggleProject(projectKey: string) {
        const selected = requestQuery.filter.filter_projects
        requestQuery = queryFromFilter({
            ...requestQuery.filter,
            filter_projects: selected.includes(projectKey)
                ? selected.filter(key => key !== projectKey)
                : [...selected, projectKey],
        })
    }

    function toggleWebsockets() {
        requestQuery = queryFromFilter({...requestQuery.filter, only_websockets: !requestQuery.filter.only_websockets})
    }

    function reset() {
        requestQuery = neutralQuery()
    }

    // One fixed height for every tag: the project chip carries avatars, the others only text, and
    // they have to line up in the same row.
    const pillBase = "flex h-8 flex-row items-center gap-1.5 rounded-full border px-3 text-sm font-mono leading-none transition-colors duration-100"
    const inactivePill = "border-gray-300 hover:bg-gray-50"
    const activePill = "border-transparent bg-gray-800 text-white!"
    // A disabled pill must not swallow pointer events, otherwise the tooltip explaining why it is
    // disabled never opens.
    const disabledPill = "opacity-50 pointer-events-none"
</script>

<div class={cn("flex flex-col gap-2", className)}>
    <div class="flex flex-row items-center gap-1">
        <Button
                variant="ghost"
                size="icon-sm"
                onclick={reset}
                disabled={isNeutral}
                aria-label={$_("userspace.requests.filter.reset")}
        >
            {#if isNeutral}
                <FunnelIcon />
            {:else}
                <FunnelXIcon />
            {/if}
        </Button>
        <div class="mx-1 h-5 w-px bg-gray-300"></div>

        <Tooltip disabled={!requestQuery.advanced}>
            <TooltipTrigger>
                {#snippet child({props})}
                    <div
                            {...props}
                            class={cn("flex flex-row items-center gap-1", requestQuery.advanced && "cursor-not-allowed")}
                    >
                        {#each FILTER_METHODS as method}
                            {@const active = requestQuery.filter.filter_methods.includes(method)}
                            <button
                                    type="button"
                                    disabled={requestQuery.advanced}
                                    onclick={() => toggleMethod(method)}
                                    class={cn(
                                        pillBase,
                                        active ? activePill : cn(inactivePill, methodColors[method as keyof typeof methodColors] ?? "text-gray-600"),
                                        requestQuery.advanced ? disabledPill : "cursor-pointer",
                                    )}
                            >{method}</button>
                        {/each}
                        <div class="mx-1 h-5 w-px bg-gray-300"></div>
                        <button
                                type="button"
                                disabled={requestQuery.advanced}
                                onclick={toggleWebsockets}
                                class={cn(
                                    pillBase,
                                    requestQuery.filter.only_websockets ? activePill : cn(inactivePill, "text-blue-700"),
                                    requestQuery.advanced ? disabledPill : "cursor-pointer",
                                )}
                        >WS</button>
                        <div class="mx-1 h-5 w-px bg-gray-300"></div>
                        <DropdownMenu.Root onOpenChange={(open) => {
                            // The projects are only needed once the list is actually opened.
                            if (open) loadProjects()
                        }}>
                            <DropdownMenu.Trigger disabled={requestQuery.advanced}>
                                {#snippet child({props})}
                                    <button
                                            {...props}
                                            type="button"
                                            aria-label={$_("userspace.requests.filter.projects.label")}
                                            class={cn(
                                                pillBase,
                                                inactivePill,
                                                "text-gray-600",
                                                // The avatar stack already shows that projects are picked, no active styling needed.
                                                selectedProjects.length > 0 && "pl-1.5",
                                                requestQuery.advanced ? disabledPill : "cursor-pointer",
                                            )}
                                    >
                                        {#if selectedProjects.length === 0}
                                            {$_("userspace.requests.filter.projects.label")}
                                        {:else}
                                            <AvatarGroup>
                                                {#each chipIcons as icon (icon.projectKey)}
                                                    <Avatar class="size-5 rounded-md after:rounded-md">
                                                        {#if icon.project}
                                                            <AvatarImage
                                                                    src="/api/projects/{icon.project.project_id}/icon"
                                                                    alt={icon.project.project_name}
                                                            />
                                                        {/if}
                                                        <AvatarFallback class="rounded-md text-[0.5rem] uppercase">
                                                            {icon.projectKey.slice(0, 2)}
                                                        </AvatarFallback>
                                                    </Avatar>
                                                {/each}
                                                {#if hiddenIcons > 0}
                                                    <AvatarGroupCount class="size-5 rounded-md text-[0.625rem]">
                                                        +{hiddenIcons}
                                                    </AvatarGroupCount>
                                                {/if}
                                            </AvatarGroup>
                                        {/if}
                                        <CaretDownIcon />
                                    </button>
                                {/snippet}
                            </DropdownMenu.Trigger>
                            <DropdownMenu.Content align="start" class="min-w-44">
                                {#if $projects === null}
                                    {#each [0, 1, 2] as placeholder (placeholder)}
                                        <div aria-hidden="true" class="flex flex-row items-center gap-2.5 py-2 pr-8 pl-3">
                                            <Skeleton class="size-4 rounded-sm" />
                                            <Skeleton class="h-3 w-24" />
                                        </div>
                                    {/each}
                                {:else if $projects.length === 0}
                                    <DropdownMenu.Group>
                                        <DropdownMenu.Item disabled>
                                            {$_("userspace.requests.filter.projects.empty")}
                                        </DropdownMenu.Item>
                                    </DropdownMenu.Group>
                                {:else}
                                    <DropdownMenu.Group>
                                        {#each $projects as project (project.project_id)}
                                            <DropdownMenu.CheckboxItem
                                                    checked={selectedProjects.includes(project.project_key)}
                                                    closeOnSelect={false}
                                                    onCheckedChange={() => toggleProject(project.project_key)}
                                            >
                                                <img src="/api/projects/{project.project_id}/icon" alt="" class="size-4 rounded-sm" />
                                                <span>{project.project_name}</span>
                                            </DropdownMenu.CheckboxItem>
                                        {/each}
                                    </DropdownMenu.Group>
                                {/if}
                            </DropdownMenu.Content>
                        </DropdownMenu.Root>
                    </div>
                {/snippet}
            </TooltipTrigger>
            <TooltipContent>{$_("userspace.requests.filter.advanced")}</TooltipContent>
        </Tooltip>
    </div>

    <RequestQueryInput bind:requestQuery />
</div>
