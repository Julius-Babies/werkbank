<script lang="ts">
    import type {RequestUpdate} from "../../state.ts";
    import {methodColors} from "$lib/components/requests/colors";

    let {
        request,
    }: {
        request: RequestUpdate,
    } = $props();
</script>

<span class={(methodColors[request.method as keyof typeof methodColors] ?? "text-gray-600") + " font-mono text-sm tracking-tight font-bold"}>
    {request.method}
</span>

<span>
    {request.uri}
</span>