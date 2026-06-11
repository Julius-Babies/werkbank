export const TIMELINE_CTX = Symbol("timeline");

export interface TimelineContext {
	horizontal: boolean;
	size: "default" | "lg";
	align: "start" | "baseline";
}

export const TIMELINE_ITEM_CTX = Symbol("timeline-item");

export interface TimelineItemContext {
	status: "complete" | "current" | "incomplete" | undefined;
	align: "start" | "baseline" | undefined;
}
