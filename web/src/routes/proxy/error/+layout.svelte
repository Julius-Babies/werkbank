<script lang="ts">
    import './layout.css';
    import faviconRed from '$lib/assets/logo_red.svg';
    import "$lib/localization/i18n";


    let {children} = $props();

</script>

<svelte:head>
    <link rel="icon" href={faviconRed}/>
</svelte:head>

<div class="flex w-full h-full">
    {@render children()}
</div>
