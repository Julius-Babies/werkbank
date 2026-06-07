<script lang="ts">
    import {
        Authentikt,
        type AuthentiktConfiguration,
        AuthentiktDebug,
        useAuthentiktContext,
        OIDCRenderer,
        DoneRenderer,
    } from "@julius-babies/authentikt-svelte";
    import {env} from "$env/dynamic/public";

    const authentiktConfiguration: AuthentiktConfiguration = {
        baseUrl: "https://" + env.PUBLIC_BASE_DOMAIN + "/api/authentikt/",
        // debug: {show_overlay: false},
        debug: false
    }
</script>

<Authentikt
        config={authentiktConfiguration}
>
    {@const authentikt = useAuthentiktContext()}

    {#if !!authentiktConfiguration.debug}
        <div class="relative h-80 overflow-y-auto">
            <AuthentiktDebug authentikt={authentikt}/>
        </div>
    {/if}

    <OIDCRenderer />
    <DoneRenderer />
</Authentikt>