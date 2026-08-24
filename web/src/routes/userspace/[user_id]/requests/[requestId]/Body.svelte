<script lang="ts">
    import {Tabs, TabsList, TabsTrigger} from "$lib/components/ui/tabs";
    import type {Request} from "./request.ts";
    import {LayoutIcon, TextAlignJustifyIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {decompressBlob} from "$lib/utils";
    import {onMount} from "svelte";
    import {JsonView} from "@zerodevx/svelte-json-view";
    import {languageOf} from "$lib/highlight";
    import SourceView from "./SourceView.svelte";

    const MANUAL_DOWNLOAD_SIZE = 1024 * 1024 * 10; // 10MB

    let {
        class: className = "",
        request,
        bodySize,
        type,
    }: {
        class?: string;
        request: Request,
        bodySize: number,
        type: "request" | "response"
    } = $props();

    type BodyType = "media" | "text" | "raw";

    let currentBodyType: BodyType = $state("media");

    let isLoading = $state(false);

    let bytes: Blob | null = $state(null);
    let text: string | null = $state(null);
    let hasContent = $derived(bytes !== null || text !== null);

    let imageUrl: string | null = $state(null);
    $effect(() => {
        if (!bytes) {
            imageUrl = null;
            return;
        }
        const url = URL.createObjectURL(bytes);
        imageUrl = url;
        return () => URL.revokeObjectURL(url);
    });

    /** The headers of the side this body belongs to. */
    function headers(): Record<string, string[]> | undefined {
        return type === "request"
            ? request.request.headers
            : request.response?.type === "success" ? request.response.headers : undefined;
    }

    function header(name: string): string | null {
        const all = headers();
        if (!all) return null;
        const key = Object.keys(all).find((k) => k.toLowerCase() === name);
        return key ? all[key]?.[0] ?? null : null;
    }

    function contentEncoding(): string | null {
        return header("content-encoding");
    }

    let contentType = $derived(header("content-type"));

    /** The grammar the preview highlights with, or `null` for a body that is not source. */
    let language = $derived(languageOf(contentType, request.request.uri));

    async function fetchBody() {
        if (isLoading) return;
        isLoading = true;
        try {
            const result = await fetch("/api/webapp/requests/" + request.request_id + "/download-body?type=" + type);
            bytes = await decompressBlob(await result.blob(), contentEncoding());
            text = await bytes.text()
        } finally {
            isLoading = false;
        }
    }

    onMount(() => {
        if (bodySize > 0 && bodySize < MANUAL_DOWNLOAD_SIZE) fetchBody();
    })
</script>

{#if request.response?.type === "success"}
    <div class="flex flex-col gap-1 min-w-0">
        <Tabs class={className} bind:value={currentBodyType}>
            <TabsList>
                <TabsTrigger value="media"><LayoutIcon /></TabsTrigger>
                <TabsTrigger value="text"><TextAlignJustifyIcon /></TabsTrigger>
                <TabsTrigger value="raw"><span class="font-light text-[0.7rem] tracking-tight">0xA</span></TabsTrigger>
            </TabsList>
        </Tabs>

        {#if bodySize > MANUAL_DOWNLOAD_SIZE && !hasContent}
            <div class="text-sm text-muted-foreground">
                This body is {Math.round(bodySize / (1024 ** 2))}MB. Please download it manually.
                <Button>
                    Download
                </Button>
            </div>
        {/if}

        <!-- No padding here: a view that scrolls carries its own, so the padding scrolls with it. -->
        <div class="bg-background text-foreground rounded-md overflow-hidden min-w-0">
            {#if currentBodyType === "media"}
                {#if (contentType + ";").startsWith("application/json;")}
                    {@const json = JSON.parse(text ?? "{}")}
                    <div class="w-full overflow-x-auto p-2 **:break-all! **:whitespace-normal!">
                        <JsonView json={json} />
                    </div>
                {:else if (contentType ?? "").toLowerCase().startsWith("image/")}
                    {#if imageUrl}
                        <div class="p-2">
                            <img src={imageUrl} alt="Body preview" class="max-w-full h-auto object-contain" />
                        </div>
                    {/if}
                {:else if language && text}
                    <SourceView class="w-full" code={text} {language} />
                {/if}
            {:else if currentBodyType === "text"}
                <div class="p-2 font-mono text-sm break-all">
                    {text}
                </div>
            {:else if currentBodyType === "raw"}
                <div class="p-2 font-mono text-sm break-all">
                    {#if bytes}
                        {#await bytes.arrayBuffer() then buffer}
                            {@const arr = new Uint8Array(buffer)}
                            {@const lineCount = Math.ceil(arr.length / 16)}
                            <div class="grid gap-0.5" style="grid-template-columns: auto 1fr auto;">
                                {#each Array(Math.min(lineCount, 500)) as _, lineIndex}
                                    {@const offset = lineIndex * 16}
                                    {@const lineBytes = arr.slice(offset, offset + 16)}
                                    {@const hexParts = Array.from(lineBytes).map(b => b.toString(16).padStart(2, '0'))}
                                    {@const hex = hexParts.join(' ').padEnd(47, ' ')}
                                    {@const ascii = Array.from(lineBytes).map(b => b >= 32 && b <= 126 ? String.fromCharCode(b) : '.').join('')}
                                    <span class="text-muted-foreground select-none pr-4">{offset.toString(16).padStart(8, '0')}</span>
                                    <span class="pr-4">{hex}</span>
                                    <span class="text-muted-foreground">{ascii}</span>
                                {/each}
                            </div>
                            {#if lineCount > 500}
                                <div class="text-muted-foreground mt-2">... {lineCount - 500} more lines</div>
                            {/if}
                        {/await}
                    {/if}
                </div>
            {/if}
        </div>
    </div>
{/if}