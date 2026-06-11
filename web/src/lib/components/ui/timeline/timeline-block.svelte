<script lang="ts">
import type { Snippet } from "svelte";
import { cn } from "$lib/utils.js";

interface Props {
	children?: Snippet;
	class?: string;
	[key: string]: unknown;
}

let { children, class: className, ...rest }: Props = $props();
</script>

<!--
	TimelineBlock spans both grid columns (col-span-full) and re-establishes
	CSS subgrid so its children can align to the parent timeline's column tracks.

	This lets nested content (avatars, icons) align with the indicator column,
	and nested text align with the content column — without any extra markup.
-->
<div
	class={cn(
        'timeline-block col-span-full grid auto-rows-auto after:col-span-full after:block after:h-6 after:content-[""] grid-cols-subgrid',
        className,
    )}
	{...rest}
>
	{@render children?.()}
</div>
