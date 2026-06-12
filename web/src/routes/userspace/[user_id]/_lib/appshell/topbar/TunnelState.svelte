<script lang="ts">
    import {tunnelState} from "../../../state.ts";

    let classes = $derived.by(() => {
        switch ($tunnelState?.active) {
            case null:
                return "text-neutral-500 border-none";
            case true:
                return "text-green-500 border-green-500 bg-green-500/10";
            case false:
                return "text-red-400 border-red-300 bg-red-400/10";
        }
    })
</script>

<div class="flex flex-row items-center gap-1">
    <span class="text-neutral-500 font-mono text-xs font-bold uppercase">WB Tunnel Status:</span>
    <div
            class={`flex flex-row items-center border rounded-sm px-1 font-mono text-xs ${classes}`}
    >
        {#if $tunnelState?.active}
            <div class="w-2 h-2 rounded-full bg-green-500 mr-1">
            </div>
        {/if}
        {$tunnelState?.active ? "Connected" : "Inactive"}
    </div>
</div>