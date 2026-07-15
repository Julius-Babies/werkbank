<script lang="ts">
    import {onMount, type Snippet} from "svelte";
    import {env} from "$env/dynamic/public";
    import {_} from 'svelte-i18n';
    import {
        Sidebar,
        SidebarContent,
        SidebarFooter,
        SidebarGroup,
        SidebarGroupContent,
        SidebarHeader,
        SidebarInset,
        SidebarMenu,
        SidebarMenuButton,
        SidebarMenuItem,
        SidebarProvider,
        SidebarTrigger,
        useSidebar
    } from "$lib/components/ui/sidebar";
    import {page} from "$app/state"
    import {EllipsisVertical, LogOutIcon, Pickaxe} from "@lucide/svelte";
    import {FolderSimpleIcon, GearIcon, HouseIcon, ListDashesIcon} from "phosphor-svelte";
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuTrigger
    } from "$lib/components/ui/dropdown-menu";
    import {Avatar, AvatarFallback, AvatarImage} from "$lib/components/ui/avatar";
    import {Separator} from "$lib/components/ui/separator";
    import {title, user} from "./state.ts";
    import webappSocket from "./webappSocket.ts";
    import SidebarItem from "./_lib/appshell/sidebar/SidebarItem.svelte";
    import {goto} from "$app/navigation";
    import TunnelState from "./_lib/appshell/topbar/TunnelState.svelte";

    const sidebar = useSidebar();

    let {
        children,
    }: {
        children: Snippet,
    } = $props();

    let cleanup: (() => void)[] = [];

    let requiresUserAuth = $derived.by(() => {
        if (page.url.pathname.startsWith("/project-opener/pending")) return false

        return true
    })

    onMount(() => {
        if (requiresUserAuth) {
            fetch("/api/me").then(meResult => {
                if (meResult.status === 401) {
                    const url = new URL(env.PUBLIC_BASE_URL)
                    url.pathname = "/api/login"
                    url.searchParams.set("redirect", window.location.href)
                    window.location.href = url.toString()
                } else if (meResult.status !== 200) {
                    console.error("Failed to fetch user data");
                } else {
                    meResult.json().then(data => user.set(data));
                }
                cleanup.push(webappSocket())
            });
        }

        return () => {
            cleanup.forEach(fn => fn())
        }
    })

    let tabTitle = $derived.by(() => {
        let result = $title + " // ";

        if ($user) {
            result += $user.username
            result += "'"
            if (!$user.username.endsWith("s")) result += "s"
            result += " "
        }
        result += "WBCloud"

        return result
    });

    $inspect(page.url.pathname)

    let showAppShell = $derived.by(() => {
        if (page.url.pathname.startsWith("/project-opener/pending")) return false

        return true
    })
</script>

<svelte:head>
    <title>{tabTitle}</title>
</svelte:head>

{#if $user}
    {#if showAppShell}
        <SidebarProvider
                style="--sidebar-width: calc(var(--spacing) * 72); --header-height: calc(var(--spacing) * 12);"
        >
            <Sidebar collapsible="offcanvas">
                <SidebarHeader class="px-4 pt-4">
                    <SidebarMenu>
                        <SidebarMenuItem>
                            <SidebarMenuButton class="data-[slot=sidebar-menu-button]:p-2!">
                                {#snippet child({props})}
                                    <a href={env.PUBLIC_BASE_URL} {...props}>
                                        <Pickaxe class="size-5!"/>
                                        <span class="text-base font-semibold">Werkbank Cloud</span>
                                    </a>
                                {/snippet}
                            </SidebarMenuButton>
                        </SidebarMenuItem>
                    </SidebarMenu>
                </SidebarHeader>

                <SidebarContent class="px-2">
                    <SidebarGroup>
                        <SidebarGroupContent class="flex flex-col gap-2">
                            <SidebarMenu>
                                <SidebarItem
                                        icon={HouseIcon}
                                        title={$_("userspace.sidebar.home")}
                                        isActive={page.url.pathname === "/"}
                                        onClick={() => goto("/")}
                                />
                                <SidebarItem
                                        icon={FolderSimpleIcon}
                                        title={$_("userspace.sidebar.projects")}
                                        isActive={page.url.pathname.startsWith("/projects")}
                                        onClick={() => goto("/projects")}
                                />
                                <SidebarItem
                                        icon={ListDashesIcon}
                                        title={$_("userspace.sidebar.requests")}
                                        isActive={page.url.pathname.startsWith("/requests")}
                                        onClick={() => goto("/requests")}
                                />
                                <SidebarItem
                                        icon={GearIcon}
                                        title={$_("userspace.sidebar.settings")}
                                        isActive={page.url.pathname.startsWith("/settings")}
                                        onClick={() => goto("/settings")}
                                />
                            </SidebarMenu>
                        </SidebarGroupContent>
                    </SidebarGroup>
                </SidebarContent>

                <SidebarFooter>
                    <SidebarMenu>
                        <SidebarMenuItem>
                            <DropdownMenu>
                                <DropdownMenuTrigger>
                                    {#snippet child({props})}
                                        <SidebarMenuButton
                                                {...props}
                                                size="lg"
                                                class="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"
                                        >
                                            <Avatar class="size-8 rounded-lg">
                                                <AvatarImage src={$user.avatar_url} alt={$user.username}/>
                                                <AvatarFallback class="rounded-lg">{$user.username}</AvatarFallback>
                                            </Avatar>
                                            <div class="grid flex-1 text-start text-sm leading-tight">
                                                <span class="truncate font-medium">{$user.username}</span>
                                                <span class="text-muted-foreground truncate text-xs">
                                                    {$user.email}
                                                </span>
                                            </div>
                                            <EllipsisVertical class="ms-auto size-4"/>
                                        </SidebarMenuButton>
                                    {/snippet}
                                </DropdownMenuTrigger>
                                <DropdownMenuContent
                                        class="w-(--bits-dropdown-menu-anchor-width) min-w-56 rounded-lg"
                                        side={sidebar?.isMobile ? "bottom" : "right"}
                                        align="end"
                                        sideOffset={4}
                                >
                                    <DropdownMenuItem onSelect={() => (window.location.href = "/api/login/logout")}>
                                        <LogOutIcon/>
                                        <span>{$_("userspace.sidebar.logout")}</span>
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </SidebarMenuItem>
                    </SidebarMenu>
                </SidebarFooter>
            </Sidebar>

            <SidebarInset>
                <div class="flex flex-col">
                    <header
                            class="flex h-(--header-height) shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-(--header-height)"
                    >
                        <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
                            <SidebarTrigger class="-ms-1"/>
                            <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4"/>
                            <h1 class="text-base font-medium">{$title}</h1>
                            <div class="ms-auto flex items-center gap-2">
                                <TunnelState />
                            </div>
                        </div>
                    </header>

                    <div class="flex flex-1 flex-col w-gfull h-full overflow-y-auto max-md:p-4 md:p-8">
                        {@render children()}
                    </div>
                </div>
            </SidebarInset>
        </SidebarProvider>
    {:else}
        {@render children()}
    {/if}
{/if}
