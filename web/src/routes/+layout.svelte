<script lang="ts">
    import './layout.css';
    import faviconBlue from '$lib/assets/logo_blue.svg';

    let {children} = $props();

</script>

<svelte:head>
    <link rel="icon" href={faviconBlue}/>
    <title>Werkbank</title>
</svelte:head>

<div class="flex w-full h-full">
    {@render children()}
</div>
