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
	TimelineSubgrid is a single row inside a TimelineBlock that aligns its
	children to the parent timeline's column tracks via CSS subgrid.

	Use this to align avatars/icons with the indicator column, and text/content
	with the content column — perfect for comment threads or nested items.
-->
<div
	class={cn(
        "timeline-subgrid col-span-full grid items-start gap-3 grid-cols-subgrid",
        className,
    )}
	{...rest}
>
	{@render children?.()}
</div>
