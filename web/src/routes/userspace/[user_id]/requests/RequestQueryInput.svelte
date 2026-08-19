<script lang="ts">
    import * as InputGroup from "$lib/components/ui/input-group";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {MagnifyingGlassIcon} from "phosphor-svelte";
    import {methodColors, statusColor} from "$lib/components/requests/colors";
    import {_} from "svelte-i18n";
    import {tick, untrack} from "svelte";
    import {fly} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import {cn} from "$lib/utils";
    import type {Project} from "../projects/getProjects.ts";
    import {queryFromString, type RequestQuery} from "./filter.ts";
    import {highlightQuery, qualifierPrefixAtCaret, type QuerySegment, valuesAtCaret} from "./query.ts";
    import {findQualifier, QUERY_QUALIFIERS, type QualifierIcon} from "./qualifiers.ts";
    import {loadProjects, projects} from "./projects.ts";

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
        /** Already translated text shown next to the query text. */
        description?: string,
        icon?: QualifierIcon,
        /** Image shown instead of an icon, e.g. a project icon. */
        image?: string,
        /** Extra classes for the entry, e.g. the color of a request method. */
        class?: string
    }

    /** What the caret is in the middle of, and which part of the draft an accepted entry replaces. */
    type Completion =
        | {kind: "qualifier", start: number, end: number, typed: string}
        | {kind: "values", start: number, end: number, typed: string, previous: string[], qualifier: string}

    let completion: Completion | null = $state(null)
    let highlighted = $state(0)

    function commit() {
        requestQuery = queryFromString(draft)
    }

    /** Completes the values of the term the caret is in, or the qualifier in front of the caret. */
    function updateCompletion() {
        const caret = input?.selectionStart ?? null
        if (caret === null) {
            completion = null
            return
        }

        const term = valuesAtCaret(draft, caret)
        const qualifier = term === null ? undefined : findQualifier(term.qualifier)

        if (term !== null && qualifier !== undefined) {
            // Only the value the caret is in gets completed, the ones before it are already set.
            const typed = term.values.slice(term.values.lastIndexOf(",") + 1)

            completion = {
                kind: "values",
                start: caret - typed.length,
                end: caret,
                typed,
                previous: term.values.split(",").slice(0, -1),
                qualifier: qualifier.query,
            }

            if (qualifier.source === "projects") loadProjects()
        } else if (term === null) {
            const prefix = qualifierPrefixAtCaret(draft, caret)
            completion = prefix === null
                ? null
                : {kind: "qualifier", start: caret - prefix.length, end: caret, typed: prefix}
        } else {
            completion = null
        }

        highlighted = 0
        measureCaret()
    }

    function projectSuggestions(loaded: Project[]): Suggestion[] {
        return loaded.map((project) => ({
            insert: project.project_key,
            query: project.project_key,
            description: project.project_name === project.project_key ? undefined : project.project_name,
            image: `/api/projects/${project.project_id}/icon`,
        }))
    }

    function candidates(current: Completion): Suggestion[] {
        if (current.kind === "qualifier") {
            return QUERY_QUALIFIERS.map((entry) => ({
                insert: `${entry.query}:`,
                query: `${entry.query}:`,
                description: entry.label ? $_(entry.label) : undefined,
                icon: entry.icon,
            }))
        }

        const qualifier = findQualifier(current.qualifier)
        if (qualifier?.source === "projects") return projectSuggestions($projects ?? [])

        return (qualifier?.values ?? []).map((value) => ({
            insert: value.query,
            query: value.query,
            description: value.label ? $_(value.label) : undefined,
            class: value.class,
        }))
    }

    let suggestions: Suggestion[] = $derived.by(() => {
        const current = completion
        if (current === null) return []

        const typed = current.typed.toLowerCase()
        const previous = current.kind === "values" ? current.previous.map((value) => value.toLowerCase()) : []

        return candidates(current)
            .filter((entry) => entry.query.toLowerCase().startsWith(typed) && !previous.includes(entry.query.toLowerCase()))
    })

    // Values that are fetched are not there on the first keystroke, so the dropdown opens with
    // placeholders instead of staying empty.
    let loading = $derived.by(() => {
        const current = completion
        if (current === null || current.kind !== "values") return false

        return findQualifier(current.qualifier)?.source === "projects" && $projects === null
    })

    let open = $derived(suggestions.length > 0 || loading)

    async function accept(suggestion: Suggestion) {
        if (completion === null) return

        const caret = completion.start + suggestion.insert.length
        draft = draft.slice(0, completion.start) + suggestion.insert + draft.slice(completion.end)
        completion = null
        commit()

        await tick()
        input?.focus()
        input?.setSelectionRange(caret, caret)

        // A completed qualifier is followed by its values, so offer them right away.
        if (suggestion.insert.endsWith(":")) updateCompletion()
    }

    function onkeydown(event: KeyboardEvent) {
        if (!open) return

        switch (event.key) {
            case "Escape":
                completion = null
                break
            case "ArrowDown":
                if (suggestions.length === 0) return
                highlighted = (highlighted + 1) % suggestions.length
                break
            case "ArrowUp":
                if (suggestions.length === 0) return
                highlighted = (highlighted - 1 + suggestions.length) % suggestions.length
                break
            case "Enter":
            case "Tab":
                if (suggestions.length === 0) return
                accept(suggestions[highlighted])
                break
            default:
                return
        }

        event.preventDefault()
    }

    // The caret can also move without changing the text, which changes what gets suggested.
    const CARET_KEYS = ["ArrowLeft", "ArrowRight", "Home", "End"]

    function onkeyup(event: KeyboardEvent) {
        if (CARET_KEYS.includes(event.key)) updateCompletion()
    }

    // The input is controlled instead of bound, so that `draft` is guaranteed to be up to date
    // when the handlers below read it.
    function oninput(event: Event & {currentTarget: HTMLInputElement}) {
        draft = event.currentTarget.value
        commit()
        updateCompletion()
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
            aria-expanded={open}
            aria-controls="request-query-suggestions"
            {oninput}
            {onkeydown}
            {onkeyup}
            onscroll={syncScroll}
            onclick={updateCompletion}
            onfocus={() => {
                editing = true
                updateCompletion()
            }}
            onblur={() => {
                editing = false
                completion = null
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

    {#if open}
        <ul
                bind:this={list}
                id="request-query-suggestions"
                role="listbox"
                aria-label={$_("userspace.requests.filter.suggestions")}
                aria-busy={loading}
                class="bg-popover text-popover-foreground absolute top-full z-50 mt-1 min-w-32 overflow-hidden rounded-2xl border p-1 shadow-md"
                style="left: {listLeft}px"
                transition:fly={{y: -4, duration: 120, easing: cubicOut}}
        >
            {#if loading}
                {#each [0, 1, 2] as placeholder (placeholder)}
                    <li aria-hidden="true" class="flex flex-row items-center gap-2 px-2 py-1">
                        <Skeleton class="size-4 rounded-sm" />
                        <Skeleton class="h-3 w-20" />
                    </li>
                {/each}
            {:else}
                {#each suggestions as suggestion, index (suggestion.query)}
                    {@const Icon = suggestion.icon}
                    <li
                            role="option"
                            aria-selected={index === highlighted}
                            class={cn("flex cursor-pointer flex-row items-center gap-2 rounded-xl px-2 py-1 text-xs",
                                index === highlighted && "bg-accent text-accent-foreground")}
                            onmousedown={(event) => {
                                // Keeps the focus, and with it the caret, inside the input.
                                event.preventDefault()
                                accept(suggestion)
                            }}
                            onmouseenter={() => highlighted = index}
                    >
                        {#if suggestion.image}
                            <img src={suggestion.image} alt="" class="size-4 rounded-sm" />
                        {:else if Icon}
                            <Icon class="text-muted-foreground" />
                        {/if}
                        <span class={cn("font-mono", suggestion.class)}>{suggestion.query}</span>
                        {#if suggestion.description}
                            <span class="text-muted-foreground">{suggestion.description}</span>
                        {/if}
                    </li>
                {/each}
            {/if}
        </ul>
    {/if}
</InputGroup.Root>
