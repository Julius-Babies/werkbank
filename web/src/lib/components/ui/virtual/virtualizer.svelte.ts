import {
	Virtualizer,
	elementScroll,
	observeElementOffset,
	observeElementRect,
	type PartialKeys,
	type VirtualizerOptions,
} from "@tanstack/virtual-core";

type ScrollElementOptions<TScrollElement extends Element, TItemElement extends Element> = PartialKeys<
	VirtualizerOptions<TScrollElement, TItemElement>,
	"observeElementRect" | "observeElementOffset" | "scrollToFn"
>;

/**
 * Creates a reactive TanStack virtualizer for Svelte, in the same shape as `createSvelteTable`:
 * the options object is read through getters, so it may be backed by runes and the virtualizer
 * follows every change.
 *
 * Reading anything off the returned virtualizer inside an effect or a template re-runs whenever it
 * notifies, so `getVirtualItems()` and `getTotalSize()` can be used like derived values.
 */
export function createVirtualizer<TScrollElement extends Element, TItemElement extends Element>(
	options: ScrollElementOptions<TScrollElement, TItemElement>,
) {
	// Bumped by the virtualizer's own change notifications; reading it in the getters below is what
	// makes scrolling and measuring reactive.
	let version = $state(0);

	function resolved(): VirtualizerOptions<TScrollElement, TItemElement> {
		return {
			observeElementRect,
			observeElementOffset,
			scrollToFn: elementScroll,
			...options,
			onChange: (instance, sync) => {
				version++;
				options.onChange?.(instance, sync);
			},
		};
	}

	const virtualizer = new Virtualizer(resolved());

	$effect.pre(() => {
		// Touching the option getters here is what subscribes this effect to them.
		virtualizer.setOptions(resolved());
		virtualizer._willUpdate();
	});

	$effect(() => virtualizer._didMount());

	return {
		get instance() {
			return virtualizer;
		},
		get items() {
			version;
			return virtualizer.getVirtualItems();
		},
		get totalSize() {
			version;
			return virtualizer.getTotalSize();
		},
		get range() {
			version;
			return virtualizer.range;
		},
		measureElement: (node: TItemElement | null) => virtualizer.measureElement(node),
		scrollToIndex: virtualizer.scrollToIndex,
		scrollToOffset: virtualizer.scrollToOffset,
	};
}
