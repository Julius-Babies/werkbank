<script lang="ts">
    import {type HighlightToken, type SourceLanguage, tokenizeSource} from "$lib/highlight";

    let {
        code,
        language,
        class: className = "",
    }: {
        code: string,
        language: SourceLanguage,
        class?: string,
    } = $props();

    /** The token classes the grammars emit, mapped onto the palette the rest of the app uses. */
    const TOKEN_CLASS: Record<string, string> = {
        attr: "text-amber-600",
        "code-inline": "text-pink-600",
        command: "text-blue-600",
        comment: "text-gray-400 italic",
        deleted: "text-red-600",
        function: "text-blue-600",
        heading: "text-purple-600 font-semibold",
        inserted: "text-green-600",
        keyword: "text-purple-600",
        link: "text-sky-600 underline",
        literal: "text-amber-600",
        meta: "text-gray-500",
        number: "text-amber-600",
        operator: "text-gray-500",
        property: "text-sky-600",
        selector: "text-purple-600",
        string: "text-green-600",
        tag: "text-red-600",
        type: "text-cyan-600",
        variable: "text-orange-600",
    };

    // `null` while the grammar is still loading, and for a body too large to highlight — both show
    // the plain source, so there is nothing to tell apart here.
    let tokens = $state.raw<HighlightToken[] | null>(null);

    $effect(() => {
        const source = code;
        const grammar = language;

        let cancelled = false;
        tokens = null;

        tokenizeSource(source, grammar)
            .then((result) => {
                if (!cancelled) tokens = result;
            })
            .catch((error) => {
                console.warn(`Could not highlight the body as ${grammar}`, error);
            });

        return () => {
            cancelled = true;
        };
    });
</script>

<!--
  Tokens are rendered as elements rather than through the library's HTML output: this is a body that
  came off someone else's server, and it must never reach an {@html}.

  The padding sits on the code, not on the scroll container, so it scrolls with the content instead
  of staying behind as a gutter the text disappears under. `inline-block` shrink-to-fits to the
  longest line — width stays `auto`, so the padding is added outside it rather than eating into it —
  and `min-w-full` keeps the block spanning the container when the content is narrower than it.
-->
<pre class={"overflow-x-auto font-mono text-sm leading-relaxed " + className}><code class="inline-block min-w-full p-2 align-top">{#if tokens}{#each tokens as token}{#if token.className}<span class={TOKEN_CLASS[token.className] ?? ""}>{token.value}</span>{:else}{token.value}{/if}{/each}{:else}{code}{/if}</code></pre>
