<script lang="ts">
    import faviconRed from '$lib/assets/logo_red.svg';
    import "$lib/localization/i18n";

    let {children} = $props();

</script>

<svelte:head>
    <link rel="icon" href={faviconRed}/>
</svelte:head>

<div class="flex flex-col w-full h-full">
    <div class="flex flex-col grow">
        {@render children()}
    </div>

    <div class="flex flex-col">
        <hr />

        <div class="flex flex-row items-center gap-2 px-4 py-2">
            <img src={faviconRed} alt="logo" class="w-6 h-6"/>
            <span class="text-sm font-mono text-accent-foreground">
                Werkbank Cloud &bullet; This response does not originate from the target service
            </span>
        </div>
    </div>
</div>
