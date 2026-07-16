<script lang="ts">
    let {
        headers
    }: {
        headers: Record<string, string[]>
    } = $props();

    const flatHeaders = $derived(Object.entries(headers).flatMap(([name, values]) =>
        values.map(value => ({name, value}))
    ));
</script>

<table class="w-full">
    <tbody>
    {#each flatHeaders as {name, value}, i (name + value)}
        <tr class={i < flatHeaders.length - 1 ? "border-b border-gray-300" : ""}>
            <td class="font-mono text-xs text-gray-600 align-top py-2 pr-1.5 whitespace-nowrap">{name}</td>
            <td class="text-xs text-gray-800 align-top py-2 truncate">{value}</td>
        </tr>
    {/each}
    </tbody>
</table>

<style>
    table {
        table-layout: fixed;
    }
</style>
