<script lang="ts">
    import {baseUrl} from "$lib";
    import {onDestroy} from "svelte";

    const WS_PATH = "/ws";

    type LogEntry = {
        id: number;
        direction: "in" | "out" | "system";
        text: string;
        time: string;
    };

    let socket: WebSocket | null = null;
    let status = $state<"disconnected" | "connecting" | "connected">("disconnected");
    let log = $state<LogEntry[]>([]);
    let nextId = 0;

    let manualMessage = $state("Hello from client");
    let autoReconnect = $state(false);
    let clientAutoSend = $state(false);

    // We closed on purpose → don't let auto-reconnect fight the user.
    let manualClose = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let autoSendTimer: ReturnType<typeof setInterval> | null = null;

    function addLog(direction: LogEntry["direction"], text: string) {
        log = [{id: nextId++, direction, text, time: new Date().toLocaleTimeString()}, ...log].slice(0, 200);
    }

    /** Derive the WebSocket URL from the configured (proxy) base URL. */
    function wsUrl(): string {
        const base = $baseUrl.replace(/\/$/, "");
        const url = new URL(base);
        url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
        url.pathname = WS_PATH;
        return url.toString();
    }

    function connect() {
        if (socket && (status === "connected" || status === "connecting")) return;

        manualClose = false;
        status = "connecting";
        const url = wsUrl();
        addLog("system", `Connecting to ${url}`);

        try {
            socket = new WebSocket(url);
        } catch (error) {
            status = "disconnected";
            addLog("system", `Failed to open socket: ${error instanceof Error ? error.message : String(error)}`);
            scheduleReconnect();
            return;
        }

        socket.onopen = () => {
            status = "connected";
            addLog("system", "Connection open");
            if (clientAutoSend) startAutoSend();
        };

        socket.onmessage = (event) => {
            addLog("in", event.data);
        };

        socket.onerror = () => {
            addLog("system", "Socket error");
        };

        socket.onclose = (event) => {
            status = "disconnected";
            stopAutoSend();
            socket = null;
            addLog("system", `Connection closed (code ${event.code})`);
            if (!manualClose) scheduleReconnect();
        };
    }

    function disconnect() {
        manualClose = true;
        clearReconnect();
        socket?.close();
    }

    function scheduleReconnect() {
        if (!autoReconnect || manualClose) return;
        clearReconnect();
        addLog("system", "Reconnecting in 3s…");
        reconnectTimer = setTimeout(connect, 3000);
    }

    function clearReconnect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
    }

    function sendMessage(text: string) {
        if (socket && status === "connected") {
            socket.send(text);
            addLog("out", text);
        } else {
            addLog("system", "Cannot send: not connected");
        }
    }

    function sendManual() {
        if (manualMessage.trim()) sendMessage(manualMessage);
    }

    function startAutoSend() {
        stopAutoSend();
        autoSendTimer = setInterval(() => {
            sendMessage(`Auto message at ${new Date().toISOString()}`);
        }, 3000);
    }

    function stopAutoSend() {
        if (autoSendTimer) {
            clearInterval(autoSendTimer);
            autoSendTimer = null;
        }
    }

    // React to the client auto-send toggle while connected.
    $effect(() => {
        if (clientAutoSend && status === "connected") {
            startAutoSend();
        } else if (!clientAutoSend) {
            stopAutoSend();
        }
    });

    onDestroy(() => {
        manualClose = true;
        clearReconnect();
        stopAutoSend();
        socket?.close();
    });
</script>

<div class="flex flex-col gap-3">
    <div class="flex items-center gap-2">
        <h2 class="text-lg font-semibold">WebSocket</h2>
        <span
            class={status === "connected"
                ? "text-green-600 dark:text-green-400"
                : status === "connecting"
                    ? "text-amber-600 dark:text-amber-400"
                    : "text-neutral-500"}
        >
            ● {status}
        </span>
    </div>

    <div class="flex flex-wrap gap-2">
        <button
            type="button"
            onclick={connect}
            disabled={status === "connected" || status === "connecting"}
            class="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-100 disabled:opacity-40 dark:border-neutral-700 dark:hover:bg-neutral-800"
        >
            Connect
        </button>
        <button
            type="button"
            onclick={disconnect}
            disabled={status === "disconnected"}
            class="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-100 disabled:opacity-40 dark:border-neutral-700 dark:hover:bg-neutral-800"
        >
            Disconnect
        </button>
    </div>

    <div class="flex flex-col gap-1 text-sm">
        <label class="flex items-center gap-2">
            <input type="checkbox" bind:checked={autoReconnect} />
            Auto-reconnect after 3s
        </label>
        <label class="flex items-center gap-2">
            <input type="checkbox" bind:checked={clientAutoSend} />
            Client auto-send (every 3s)
        </label>
    </div>

    <div class="flex gap-2">
        <input
            type="text"
            bind:value={manualMessage}
            onkeydown={(e) => e.key === "Enter" && sendManual()}
            placeholder="Message to send"
            class="flex-1 rounded border border-neutral-300 px-2 py-1 text-sm dark:border-neutral-700"
        />
        <button
            type="button"
            onclick={sendManual}
            disabled={status !== "connected"}
            class="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-100 disabled:opacity-40 dark:border-neutral-700 dark:hover:bg-neutral-800"
        >
            Send
        </button>
    </div>

    <div class="flex flex-col gap-1">
        <div class="flex items-center justify-between">
            <h3 class="text-sm font-semibold">Messages</h3>
            {#if log.length > 0}
                <button type="button" onclick={() => (log = [])} class="text-xs text-neutral-500 hover:underline">
                    Clear
                </button>
            {/if}
        </div>
        {#if log.length === 0}
            <p class="text-sm text-neutral-500">No messages yet.</p>
        {:else}
            <ul class="flex max-h-72 flex-col gap-1 overflow-y-auto rounded border border-neutral-200 p-2 text-xs dark:border-neutral-800">
                {#each log as entry (entry.id)}
                    <li class="flex gap-2">
                        <span class="text-neutral-400">{entry.time}</span>
                        <span
                            class={entry.direction === "in"
                                ? "font-semibold text-blue-600 dark:text-blue-400"
                                : entry.direction === "out"
                                    ? "font-semibold text-green-600 dark:text-green-400"
                                    : "font-semibold text-neutral-500"}
                        >
                            {entry.direction === "in" ? "▼ in" : entry.direction === "out" ? "▲ out" : "•"}
                        </span>
                        <span class="whitespace-pre-wrap break-words">{entry.text}</span>
                    </li>
                {/each}
            </ul>
        {/if}
    </div>
</div>
