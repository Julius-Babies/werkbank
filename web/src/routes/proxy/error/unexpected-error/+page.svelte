<script lang="ts">
    // The API replaces this placeholder token in the prerendered HTML with the tunnel host's error
    // message and checkpoint timeline before serving the page (see ProxyPlugin.tunnelDetailsHtml).
    const details = "__WERKBANK_TUNNEL_DETAILS__";
</script>

<svelte:head>
    <title>Unexpected error</title>
</svelte:head>

<div class="flex flex-col w-full h-full p-12">
    <div class="text-4xl">
        An unexpected error occurred
    </div>
    <div>
        The tunnel host ran into an unexpected error while handling this request. The details below show
        how far it got before it failed.
    </div>

    <div class="mt-8 max-w-3xl rounded-sm bg-red-50 p-4">
        <!-- eslint-disable-next-line svelte/no-at-html-tags -->
        {@html details}
    </div>
</div>
