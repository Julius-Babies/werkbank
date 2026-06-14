<script lang="ts">
    import CardHead from "../_lib/CardHead.svelte";
    import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "$lib/components/ui/card";
    import {_} from "svelte-i18n";
    import {page} from "$app/state";
    import {Button} from "$lib/components/ui/button";
    import {Alert, AlertDescription, AlertTitle} from "$lib/components/ui/alert";
    import {UserSwitchIcon} from "phosphor-svelte";

    const projectId = page.url.searchParams.get("project_id")!;
    const projectName = page.url.searchParams.get("project_name")!;
    const ownerUsername = page.url.searchParams.get("owner_username")!;
    const ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url")!;
    const wbCloudAuthUrl = page.url.searchParams.get("wbcloud_auth_url")!;
    const isWrongUserLoggedIn = page.url.searchParams.get("is_wrong_user_logged_in") === "true";
</script>

<svelte:head>
    <title>{$_("proxy.auth.account-required.title", { values: { projectName, ownerUsername } })}</title>
</svelte:head>

<div class="flex flex-col items-center justify-center w-full h-full">
    <Card class="w-full max-w-sm">
        <CardHeader>
            <CardTitle>
                <div class="flex flex-col">
                    <CardHead projectId={projectId} ownerProfileIcon={ownerAvatarUrl} ownerUsername={ownerUsername} />
                    <span>{$_("proxy.auth.account-required.title", { values: { projectName, ownerUsername } })}</span>
                </div>
            </CardTitle>
            <CardDescription>{$_("proxy.auth.account-required.description")}</CardDescription>
        </CardHeader>

        {#if isWrongUserLoggedIn}
            <Alert variant="destructive">
                <UserSwitchIcon class="size-4" />
                <AlertTitle>{$_("proxy.auth.password.wrong_user.title")}</AlertTitle>
                <AlertDescription>{$_("proxy.auth.password.wrong_user.description")}</AlertDescription>
            </Alert>
        {/if}

        <CardContent>
            <Button href={wbCloudAuthUrl} variant="default" class="w-full">{$_("proxy.auth.account-required.button.wbcloud")}</Button>
        </CardContent>
    </Card>
</div>