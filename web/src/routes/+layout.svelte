<script lang="ts">
    import './layout.css';
    import favicon from '$lib/assets/favicon.svg';
    import "$lib/localization/i18n";


    let {children} = $props();

</script>

<svelte:head>
    <link rel="icon" href={favicon}/>
</svelte:head>

<div class="flex w-full h-full">
    {@render children()}
</div>
