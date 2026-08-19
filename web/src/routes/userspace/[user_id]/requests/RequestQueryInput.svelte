<script lang="ts">
    import * as InputGroup from "$lib/components/ui/input-group";
    import {MagnifyingGlassIcon} from "phosphor-svelte";
    import {methodColors, statusColor} from "$lib/components/requests/colors";
    import {_} from "svelte-i18n";
    import {tick, untrack} from "svelte";
    import {fly} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import {cn} from "$lib/utils";
    import {queryFromString, type RequestQuery} from "./filter.ts";
    import {highlightQuery, qualifierPrefixAtCaret, type QuerySegment, valuesAtCaret} from "./query.ts";
    import {findQualifier, QUERY_QUALIFIERS, type QualifierIcon} from "./qualifiers.ts";

    let {
        requestQuery = $bindable(),
        class: className,
    }: {
        requestQuery: RequestQuery,
        class?: string
    } = $props()

    let input: HTMLInputElement | null = $state(null)
    let overlay: HTMLDivElement | null = $state(null)

    // The draft is what the user types. It is only taken over from the outside while the input is
    // not being edited, so pressing a filter button updates it but typing is never overwritten.
    let draft = $state(requestQuery.query)
    let editing = $state(false)

    $effect(() => {
        const query = requestQuery.query
        untrack(() => {
            if (!editing) draft = query
        })
    })

    let segments = $derived(highlightQuery(draft))

    /** Colors a value the same way the request table colors it. */
    function valueColor(segment: QuerySegment): string {
        if (segment.value === undefined) return ""

        if (segment.qualifier === "method") {
            return methodColors[segment.value.toUpperCase() as keyof typeof methodColors] ?? ""
        }

        if (segment.qualifier === "status") {
            const status = Number(segment.value)
            return Number.isInteger(status) ? statusColor(status) : ""
        }

        return ""
    }

    interface Suggestion {
        /** Text that replaces the completed part of the query. */
        insert: string,
        /** What the entry shows, i.e. what ends up in the query. */
        query: string,
        /** i18n key of the label shown next to the query text. */
        label?: string,
        icon?: QualifierIcon,
        /** Extra classes for the entry, e.g. the color of a request method. */
        class?: string
    }

    let suggestions: Suggestion[] = $state([])
    let highlighted = $state(0)

    // Part of the draft the accepted suggestion replaces. Not reactive, it is only read on accept.
    let completing: {start: number, end: number} | null = null

    function commit() {
        requestQuery = queryFromString(draft)
    }

    /** Completes the values of the term the caret is in, or the qualifier in front of the caret. */
    function updateSuggestions() {
        const caret = input?.selectionStart ?? null
        if (caret === null) {
            suggestions = []
            return
        }

        const term = valuesAtCaret(draft, caret)
        const values = term === null ? undefined : findQualifier(term.qualifier)?.values
        const qualifier = term === null ? qualifierPrefixAtCaret(draft, caret) : null

        if (term !== null && values !== undefined) {
            // Only the value the caret is in gets completed, the ones before it are already set.
            const typed = term.values.slice(term.values.lastIndexOf(",") + 1).toLowerCase()
            const previous = term.values.split(",").slice(0, -1).map((value) => value.toLowerCase())

            completing = {start: caret - typed.length, end: caret}
            suggestions = values
                .filter((value) => value.query.toLowerCase().startsWith(typed) && !previous.includes(value.query.toLowerCase()))
                .map((value) => ({insert: value.query, query: value.query, label: value.label, class: value.class}))
        } else if (qualifier !== null) {
            const typed = qualifier.toLowerCase()

            completing = {start: caret - qualifier.length, end: caret}
            suggestions = QUERY_QUALIFIERS
                .filter((entry) => entry.query.startsWith(typed))
                .map((entry) => ({insert: `${entry.query}:`, query: `${entry.query}:`, label: entry.label, icon: entry.icon}))
        } else {
            suggestions = []
            return
        }

        highlighted = 0
        measureCaret()
    }

    async function accept(suggestion: Suggestion) {
        if (!completing) return

        const caret = completing.start + suggestion.insert.length
        draft = draft.slice(0, completing.start) + suggestion.insert + draft.slice(completing.end)
        suggestions = []
        commit()

        await tick()
        input?.focus()
        input?.setSelectionRange(caret, caret)

        // A completed qualifier is followed by its values, so offer them right away.
        if (suggestion.insert.endsWith(":")) updateSuggestions()
    }

    function onkeydown(event: KeyboardEvent) {
        if (suggestions.length === 0) return

        switch (event.key) {
            case "ArrowDown":
                highlighted = (highlighted + 1) % suggestions.length
                break
            case "ArrowUp":
                highlighted = (highlighted - 1 + suggestions.length) % suggestions.length
                break
            case "Enter":
            case "Tab":
                accept(suggestions[highlighted])
                break
            case "Escape":
                suggestions = []
                break
            default:
                return
        }

        event.preventDefault()
    }

    // The caret can also move without changing the text, which changes what gets suggested.
    const CARET_KEYS = ["ArrowLeft", "ArrowRight", "Home", "End"]

    function onkeyup(event: KeyboardEvent) {
        if (CARET_KEYS.includes(event.key)) updateSuggestions()
    }

    // The input is controlled instead of bound, so that `draft` is guaranteed to be up to date
    // when the handlers below read it.
    function oninput(event: Event & {currentTarget: HTMLInputElement}) {
        draft = event.currentTarget.value
        commit()
        updateSuggestions()
        syncScroll()
    }

    function syncScroll() {
        if (overlay && input) overlay.scrollLeft = input.scrollLeft
    }

    let list: HTMLUListElement | null = $state(null)
    let caretLeft = $state(0)
    let listLeft = $state(0)
    let textMetrics: CanvasRenderingContext2D | null = null

    /** Offset of the caret within the input group, so the dropdown can open right below it. */
    function measureCaret() {
        if (!input) return

        const style = getComputedStyle(input)
        textMetrics ??= document.createElement("canvas").getContext("2d")
        if (!textMetrics) return

        textMetrics.font = style.font
        const text = textMetrics.measureText(draft.slice(0, input.selectionStart ?? 0)).width
        caretLeft = input.offsetLeft + parseFloat(style.paddingLeft) + text - input.scrollLeft
    }

    // Keep the dropdown inside the input group even when the caret is near its right edge.
    $effect(() => {
        if (!list || !input) return

        const available = (input.parentElement?.clientWidth ?? 0) - list.offsetWidth
        listLeft = Math.max(0, Math.min(caretLeft, available))
    })

    // The highlight overlay has to sit exactly on top of the input, which is positioned by the
    // input group, so its box and text metrics are copied from the input itself.
    let overlayStyle = $state("")

    function measure() {
        if (!input) return

        const style = getComputedStyle(input)
        overlayStyle = [
            `left: ${input.offsetLeft}px`,
            `top: ${input.offsetTop}px`,
            `width: ${input.offsetWidth}px`,
            `height: ${input.offsetHeight}px`,
            `padding-left: ${style.paddingLeft}`,
            `padding-right: ${style.paddingRight}`,
            `font: ${style.font}`,
            `letter-spacing: ${style.letterSpacing}`,
        ].join("; ")
    }

    $effect(() => {
        if (!input) return

        measure()
        const observer = new ResizeObserver(measure)
        observer.observe(input)
        return () => observer.disconnect()
    })
</script>

<InputGroup.Root class={className}>
    <InputGroup.Addon>
        <MagnifyingGlassIcon />
    </InputGroup.Addon>

    <InputGroup.Input
            bind:ref={input}
            value={draft}
            class="font-mono text-transparent caret-foreground"
            spellcheck={false}
            autocapitalize="off"
            autocomplete="off"
            placeholder={$_("userspace.requests.filter.placeholder")}
            aria-autocomplete="list"
            aria-expanded={suggestions.length > 0}
            aria-controls="request-query-suggestions"
            {oninput}
            {onkeydown}
            {onkeyup}
            onscroll={syncScroll}
            onclick={updateSuggestions}
            onfocus={() => {
                editing = true
                updateSuggestions()
            }}
            onblur={() => {
                editing = false
                suggestions = []
                draft = requestQuery.query
            }}
    />

    <!-- Lies on top of the transparent input text and colors it; never takes pointer events. -->
    <div
            bind:this={overlay}
            aria-hidden="true"
            class="pointer-events-none absolute flex items-center overflow-hidden"
            style={overlayStyle}
    >
        <span class="whitespace-pre">
            {#each segments as segment, index (index)}
                <span
                        class={cn(
                            (segment.role === "qualifier" || segment.role === "operator") && "text-muted-foreground",
                            segment.role === "operator" && "font-semibold",
                            valueColor(segment),
                        )}
                >{segment.text}</span>
            {/each}
        </span>
    </div>

    {#if suggestions.length > 0}
        <ul
                bind:this={list}
                id="request-query-suggestions"
                role="listbox"
                aria-label={$_("userspace.requests.filter.suggestions")}
                class="bg-popover text-popover-foreground absolute top-full z-50 mt-1 min-w-32 overflow-hidden rounded-2xl border p-1 shadow-md"
                style="left: {listLeft}px"
                transition:fly={{y: -4, duration: 120, easing: cubicOut}}
        >
            {#each suggestions as suggestion, index (suggestion.query)}
                {@const Icon = suggestion.icon}
                <li
                        role="option"
                        aria-selected={index === highlighted}
                        class={cn("flex cursor-pointer flex-row items-center gap-2 rounded-xl p-2 text-xs",
                            index === highlighted && "bg-accent text-accent-foreground")}
                        onmousedown={(event) => {
                            // Keeps the focus, and with it the caret, inside the input.
                            event.preventDefault()
                            accept(suggestion)
                        }}
                        onmouseenter={() => highlighted = index}
                >
                    {#if Icon}
                        <Icon class="text-muted-foreground" />
                    {/if}
                    <span class={cn("font-mono", suggestion.class)}>{suggestion.query}</span>
                    {#if suggestion.label}
                        <span class="text-muted-foreground">{$_(suggestion.label)}</span>
                    {/if}
                </li>
            {/each}
        </ul>
    {/if}
</InputGroup.Root>
