<script lang="ts">
import type { Snippet } from "svelte";
import { setContext } from "svelte";
import { cn } from "$lib/utils.js";
import { TIMELINE_CTX, type TimelineContext } from "./timeline-ctx.js";

interface Props {
	horizontal?: boolean;
	size?: "default" | "lg";
	align?: "start" | "baseline";
	children?: Snippet;
	class?: string;
	[key: string]: unknown;
}

let {
	horizontal = false,
	size = "default",
	align = "baseline",
	children,
	class: className,
	...rest
}: Props = $props();

setContext<TimelineContext>(TIMELINE_CTX, {
	get horizontal() {
		return horizontal;
	},
	get size() {
		return size;
	},
	get align() {
		return align;
	},
});
</script>

<!--
	Vertical: 2-column grid — col 1 (indicator, auto width) | col 2 (content, 1fr)
	TimelineItems use display:contents so their children land directly in this grid.

	Horizontal: auto-fill equal columns, 2 rows [auto_1fr].
	Each TimelineItem is a real grid item spanning 2 rows via subgrid,
	stacking its indicator (row 1) and content (row 2).
-->
<div
	data-horizontal={horizontal || undefined}
	data-size={size}
	class={cn(
        "timeline grid",
        horizontal
            ? "auto-cols-fr grid-flow-col grid-rows-[auto_0.75rem_1fr]"
            : "grid-cols-[auto_1fr]",
        !horizontal && "gap-x-4",
        className,
    )}
	{...rest}
>
	{@render children?.()}
</div>
