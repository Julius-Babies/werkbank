<script lang="ts">
    import { SidebarMenuButton, SidebarMenuItem } from "$lib/components/ui/sidebar";
    import type { Component } from "svelte";

    let {
        icon,
        title,
        isActive,
        onClick,
    }: {
        icon: Component<any>,
        title: string,
        isActive: boolean,
        onClick?: () => void,
    } = $props();

    let classes = $derived(isActive ? "bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground active:bg-primary/90 active:text-primary-foreground min-w-8 duration-200 ease-linear" : "");
</script>

<SidebarMenuItem class="flex items-center gap-2">
    <SidebarMenuButton
            class={classes}
            tooltipContent={title}
            onclick={onClick}
    >
        <svelte:component this={icon} class="!size-4.5" weight={isActive ? "fill" : "regular"} />
        <span>{title}</span>
    </SidebarMenuButton>
</SidebarMenuItem>