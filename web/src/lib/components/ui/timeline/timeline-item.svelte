<script lang="ts">
import type { Snippet } from "svelte";
import { getContext, setContext } from "svelte";
import {
	TIMELINE_CTX,
	TIMELINE_ITEM_CTX,
	type TimelineContext,
	type TimelineItemContext,
} from "./timeline-ctx.js";

interface Props {
	status?: "complete" | "current" | "incomplete";
	align?: "start" | "baseline";
	children?: Snippet;
	[key: string]: unknown;
}

let { status, align, children, ...rest }: Props = $props();

const timelineCtx = getContext<TimelineContext>(TIMELINE_CTX);

setContext<TimelineItemContext>(TIMELINE_ITEM_CTX, {
	get status() {
		return status;
	},
	get align() {
		return align;
	},
});
</script>

<!--
	Vertical: display:contents makes this element transparent to the grid layout.
	Its children (indicator + content) participate directly in the parent Timeline's
	2-column grid.

	Horizontal: a real grid item that stacks indicator (row 1) and content (row 2)
	using CSS subgrid, so items share the same 2-row track heights.
-->
{#if timelineCtx?.horizontal}
	<div
		class="timeline-item group/timeline-item grid grid-cols-subgrid row-[span_3]"
		{...rest}
	>
		{@render children?.()}
	</div>
{:else}
	<div
		class="timeline-item group/timeline-item"
		style="display: contents"
		{...rest}
	>
		{@render children?.()}
	</div>
{/if}
