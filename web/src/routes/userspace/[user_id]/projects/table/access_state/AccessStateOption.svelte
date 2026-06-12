<script lang="ts">
    import {DropdownMenuItem} from "$lib/components/ui/dropdown-menu";
    import type {Component} from "svelte";
    import {CheckIcon, Loader} from "@lucide/svelte";

    let {
        title,
        description,
        icon,
        isLoading,
        check,
        textColor,
        onClick,
    }: {
        title: string,
        description: string,
        icon: Component,
        isLoading: boolean,
        check: boolean,
        textColor: string,
        onClick: () => Promise<void>,
    } = $props();
</script>

<DropdownMenuItem onclick={onClick} closeOnSelect={false}>
    <div class="flex flex-row gap-2 items-center justify-between w-full">
        <div class="flex flex-row gap-3 items-center justify-start">
            <div class={textColor}>
                {#if !isLoading}
                    {@const Icon = icon}
                    <Icon weight="fill" size="14"/>
                {:else}
                    <div class="w-3.5 h-3.5 animate-spin">
                        <Loader size={14} />
                    </div>
                {/if}
            </div>
            <div class="flex flex-col">
                <span class="font-normal">
                    {title}
                </span>
                <span class="text-xs text-gray-500">
                    {description}
                </span>
            </div>
        </div>
        {#if check}
            <CheckIcon/>
        {/if}
    </div>
</DropdownMenuItem>