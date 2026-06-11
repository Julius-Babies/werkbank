<script lang="ts">
import type { Snippet } from "svelte";
import { getContext } from "svelte";
import { tv } from "tailwind-variants";
import { cn } from "$lib/utils.js";
import {
	TIMELINE_CTX,
	TIMELINE_ITEM_CTX,
	type TimelineContext,
	type TimelineItemContext,
} from "./timeline-ctx.js";

type Variant = "default" | "bare";

interface Props {
	variant?: Variant;
	children?: Snippet;
	class?: string;
	[key: string]: unknown;
}

let {
	variant = "default",
	children,
	class: className,
	...rest
}: Props = $props();

const timelineCtx = getContext<TimelineContext>(TIMELINE_CTX);
const itemCtx = getContext<TimelineItemContext>(TIMELINE_ITEM_CTX);

const indicatorVariants = tv({
	slots: {
		root: "timeline-ind-cell relative",
		dot: "relative z-10 flex shrink-0 items-center justify-center rounded-full",
	},
	variants: {
		horizontal: {
			true: {
				root: "flex items-center justify-center",
			},
			false: {
				root: "grid justify-items-center",
			},
		},
		size: {
			default: {
				dot: "size-2.5",
			},
			icon: {
				dot: "size-5",
			},
			lg: {
				dot: "size-8 text-sm font-medium",
			},
		},
		variant: {
			default: {
				dot: "bg-zinc-300 dark:bg-zinc-600",
			},
			bare: {},
		},
	},
	defaultVariants: {
		horizontal: false,
		size: "default",
		variant: "default",
	},
});

const statusClasses: Record<string, string> = {
	complete: "bg-primary",
	current:
		"bg-primary ring-2 ring-primary ring-offset-2 ring-offset-background",
	incomplete: "bg-muted-foreground/30",
};

const statusClass = $derived(
	variant === "bare" || !itemCtx?.status
		? ""
		: (statusClasses[itemCtx.status] ?? ""),
);

const size = $derived(
	variant === "bare"
		? undefined
		: timelineCtx?.size === "lg"
			? "lg"
			: children
				? "icon"
				: "default",
);

const slots = $derived(
	indicatorVariants({
		horizontal: Boolean(timelineCtx?.horizontal),
		size,
		variant,
	}),
);

// Vertical alignment: align-self on the dot inside the ind-cell grid.
// The ind-cell is a grid that stretches to the full row height (set by col 2).
// Both supported alignments currently resolve to self-start.
const dotAlignSelf = $derived((): string => {
	if (timelineCtx?.horizontal) return "";
	return "self-start";
});
</script>

<!--
	Vertical: the ind-cell is a single-column grid (display:grid) that stretches
	to the full row height because grid items default to align-self:stretch.
	The dot is placed with align-self for vertical alignment.
	The connector is absolutely positioned top-0/bottom-0 behind the dot,
	spanning the full cell height (= the full grid row height).

	Horizontal: position:relative flex, dot centered, connector absolute.
-->
<div class={slots.root()} {...rest}>
	<!-- Connector line (rendered first so dot sits on top via z-index) -->
	{#if timelineCtx?.horizontal}
		<div
			class="connector absolute top-1/2 left-1/2 right-[-50%] h-px -translate-y-1/2 bg-border group-last/timeline-item:hidden"
		></div>
	{:else}
		<div
			class="connector absolute top-0 bottom-0 left-1/2 w-px -translate-x-1/2 bg-border group-last/timeline-item:hidden"
		></div>
	{/if}

	<!-- The indicator dot/icon -->
	<div
		class={cn(
            slots.dot(),
            className,
            statusClass,
            !timelineCtx?.horizontal && dotAlignSelf(),
        )}
	>
		{#if children}
			{@render children()}
		{/if}
	</div>
</div>
