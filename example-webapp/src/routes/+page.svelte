<script lang="ts">
    import {baseUrl} from "$lib";
    import WebSocketPanel from "$lib/WebSocketPanel.svelte";

    const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"] as const;
    type Method = typeof METHODS[number];

    type HistoryEntry = {
        id: number;
        method: Method;
        url: string;
        state: "pending" | "success" | "error";
        status?: number;
        statusText?: string;
        responseType?: string;
        responseMessage: string;
    };

    let history = $state<HistoryEntry[]>([]);
    let nextId = 0;

    async function sendRequest(method: Method) {
        // Requests target the configured base URL (the proxy), not localhost.
        const url = `${$baseUrl.replace(/\/$/, "")}/api/echo`;
        const entry: HistoryEntry = {
            id: nextId++,
            method,
            url,
            state: "pending",
            responseMessage: "…"
        };
        history = [entry, ...history];

        try {
            const init: RequestInit = {method};
            if (method !== "GET") {
                init.headers = {"content-type": "application/json"};
                init.body = JSON.stringify({sentAt: new Date().toISOString()});
            }

            const response = await fetch(url, init);
            const contentType = response.headers.get("content-type") ?? "unknown";

            let message: string;
            if (contentType.includes("application/json")) {
                message = JSON.stringify(await response.json());
            } else {
                message = await response.text();
            }

            update(entry.id, {
                state: response.ok ? "success" : "error",
                status: response.status,
                statusText: response.statusText,
                responseType: contentType,
                responseMessage: message
            });
        } catch (error) {
            update(entry.id, {
                state: "error",
                responseType: "network error",
                responseMessage: error instanceof Error ? error.message : String(error)
            });
        }
    }

    function update(id: number, patch: Partial<HistoryEntry>) {
        history = history.map((e) => (e.id === id ? {...e, ...patch} : e));
    }

    function clearHistory() {
        history = [];
    }
</script>

<div class="mx-auto flex max-w-3xl flex-col gap-6 p-6">
    <div class="flex flex-col gap-1">
        <label for="base-url" class="text-sm font-medium">Base URL (proxy target)</label>
        <input
            id="base-url"
            type="text"
            bind:value={$baseUrl}
            class="w-full rounded border border-neutral-300 px-2 py-1 dark:border-neutral-700"
        />
    </div>

    <div>
        <h1 class="text-xl font-semibold">What is this?</h1>
        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            This webapp provides a way to perform requests via the werkbank proxy to enable
            debugging of proxy requests.
        </p>
    </div>

    <div class="flex flex-wrap gap-2">
        {#each METHODS as method (method)}
            <button
                type="button"
                onclick={() => sendRequest(method)}
                class="rounded border border-neutral-300 px-3 py-1.5 font-mono text-sm hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-800"
            >
                {method}
            </button>
        {/each}
    </div>

    <div class="flex flex-col gap-2">
        <div class="flex items-center justify-between">
            <h2 class="text-lg font-semibold">History</h2>
            {#if history.length > 0}
                <button
                    type="button"
                    onclick={clearHistory}
                    class="text-sm text-neutral-500 hover:underline"
                >
                    Clear
                </button>
            {/if}
        </div>

        {#if history.length === 0}
            <p class="text-sm text-neutral-500">No requests yet.</p>
        {:else}
            <ul class="flex flex-col gap-2">
                {#each history as entry (entry.id)}
                    <li class="rounded border border-neutral-200 p-3 text-sm dark:border-neutral-800">
                        <div class="flex flex-wrap items-center gap-2">
                            <span class="font-mono font-semibold">{entry.method}</span>
                            {#if entry.state === "pending"}
                                <span class="text-neutral-500">pending…</span>
                            {:else}
                                <span
                                    class={entry.state === "success"
                                        ? "text-green-600 dark:text-green-400"
                                        : "text-red-600 dark:text-red-400"}
                                >
                                    {entry.status ?? "—"} {entry.statusText ?? ""}
                                </span>
                            {/if}
                        </div>
                        <div class="mt-1 truncate font-mono text-xs text-neutral-500" title={entry.url}>
                            {entry.url}
                        </div>
                        {#if entry.responseType}
                            <div class="mt-1 text-xs text-neutral-500">type: {entry.responseType}</div>
                        {/if}
                        <pre class="mt-1 overflow-x-auto whitespace-pre-wrap break-words text-xs">{entry.responseMessage}</pre>
                    </li>
                {/each}
            </ul>
        {/if}
    </div>

    <hr class="border-neutral-200 dark:border-neutral-800" />

    <WebSocketPanel />
</div>
