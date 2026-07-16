<script lang="ts">
    import {title} from "../state.ts";
    import AccessKeys from "./_access_keys/AccessKeys.svelte";
    import {_} from "svelte-i18n";
    import Page from "../_lib/appshell/page/Page.svelte";
    import PageHead from "../_lib/appshell/page/PageHead.svelte";
    import PageTitle from "../_lib/appshell/page/PageTitle.svelte";
    import PageContent from "../_lib/appshell/page/PageContent.svelte";

    $effect(() => {
        title.set($_("userspace.settings.title"))
    })
</script>

<Page>
    <PageHead>
        <PageTitle>{$_("userspace.settings.title")}</PageTitle>
    </PageHead>

    <PageContent>
        <h2 class="text-2xl font-bold flex-1">{$_("userspace.settings.access.title")}</h2>
        <p class="text-sm text-gray-500">{$_("userspace.settings.access.description")}</p>

        <AccessKeys />
    </PageContent>
</Page>
