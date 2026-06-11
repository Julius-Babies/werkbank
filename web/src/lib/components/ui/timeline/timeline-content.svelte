<script lang="ts">
import type { Snippet } from "svelte";
import { getContext } from "svelte";
import { cn } from "$lib/utils.js";
import { TIMELINE_CTX, type TimelineContext } from "./timeline-ctx.js";

interface Props {
	children?: Snippet;
	class?: string;
	[key: string]: unknown;
}

let { children, class: className, ...rest }: Props = $props();

const timelineCtx = getContext<TimelineContext>(TIMELINE_CTX);
</script>

<!--
	This is the content column cell (grid column 2).

	Vertical: the parent timeline's column gap creates the visual separation from
	the indicator. This cell becomes a small grid with an empty trailing row so
	the row height includes inter-item spacing and the connector can span it.

	Horizontal: the parent timeline defines a dedicated spacer row between the
	indicator row and the content row. text-center aligns text under each step.
-->
<div
	class={cn(
        "timeline-content-cell",
        timelineCtx?.horizontal
            ? "row-start-3 text-center"
            : 'grid auto-rows-auto after:block after:h-6 after:content-[""]',
        className,
    )}
	{...rest}
>
	{@render children?.()}
</div>
