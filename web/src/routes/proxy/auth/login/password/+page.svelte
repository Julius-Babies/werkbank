<script lang="ts">
    import {Card, CardDescription, CardHeader, CardTitle} from "$lib/components/ui/card";
    import {page} from "$app/state";
    import {Avatar, AvatarFallback, AvatarImage} from "$lib/components/ui/avatar";
    import {Field, FieldDescription, FieldError, FieldSeparator, FieldSet} from "$lib/components/ui/field";
    import {Label} from "$lib/components/ui/label";
    import {Input} from "$lib/components/ui/input";
    import {Button} from "$lib/components/ui/button";
    import {Alert, AlertDescription, AlertTitle} from "$lib/components/ui/alert";
    import {UserSwitchIcon} from "phosphor-svelte";
    import { slide } from "svelte/transition";
    import { Loader } from "@lucide/svelte";
    import {_} from "svelte-i18n";
    import {onMount, tick} from "svelte";

    const proxyAuthSessionId = page.url.searchParams.get("proxy_auth_session_id");
    const projectId = page.url.searchParams.get("project_id");
    const projectName = page.url.searchParams.get("project_name");
    const ownerUsername = page.url.searchParams.get("owner_username");
    const ownerAvatarUrl = page.url.searchParams.get("owner_avatar_url");
    const isWrongUserLoggedIn = page.url.searchParams.get("is_wrong_user_logged_in") === "true";

    let currentState: "ready" | "loading" | "error" | "invalid_password" = $state("ready");
    let passwordValue = $state("");
    let passwordErrors: { message?: string }[] = $state([]);
    let passwordInput = $state<HTMLInputElement | null>(null);

    onMount(() => {
        passwordInput?.focus();
    });

    $effect(() => {
        if (currentState === "invalid_password" || currentState === "error") {
            tick().then(() => passwordInput?.focus());
        }
    });

    async function handleSubmit() {
        if (currentState === "loading") return

        if (!passwordValue) {
            passwordErrors = [{ message: $_("proxy.auth.password.field.error.empty") }];
            return;
        }
        passwordErrors = [];
        currentState = "loading"

        try {
            const result = await fetch(`/api/proxy/auth/password?proxy_auth_session_id=${proxyAuthSessionId}`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    password: passwordValue,
                }),
            })

            if (result.status === 401) {
                currentState = "invalid_password"
                passwordErrors = [{ message: $_("proxy.auth.password.field.error.invalid") }];
                return
            } else if (result.status !== 200) {
                currentState = "error"
                return
            } else {
                const data = await result.json()
                window.location.href = data.redirect_uri
                currentState = "ready"
            }
        } catch (e) {
            currentState = "error"
            return
        }
    }
</script>

<div class="flex flex-col items-center justify-center w-full h-full">
    <Card class="w-full max-w-sm">
        <CardHeader>
            <CardTitle>
                <div class="flex flex-col">
                    <div class="relative self-center pb-4">
                        <img src={"/api/projects/" + projectId + "/icon"} alt="Project Icon" class="size-12 rounded-md"/>
                        <Avatar class="size-8 rounded-full absolute top-8 -right-4 border-4 border-background">
                            <AvatarImage src={ownerAvatarUrl} alt={ownerUsername}/>
                            <AvatarFallback class="rounded-lg">{ownerUsername}</AvatarFallback>
                        </Avatar>
                    </div>
                    <span>{$_("proxy.auth.password.title", { values: { projectName, ownerUsername } })}</span>
                </div>
            </CardTitle>
            <CardDescription>{$_("proxy.auth.password.description")}</CardDescription>

            {#if isWrongUserLoggedIn}
                <Alert variant="destructive">
                    <UserSwitchIcon class="size-4" />
                    <AlertTitle>{$_("proxy.auth.password.wrong_user.title")}</AlertTitle>
                    <AlertDescription>{$_("proxy.auth.password.wrong_user.description")}</AlertDescription>
                </Alert>
            {/if}

            {#if currentState === "error"}
                <Alert variant="destructive">
                    <AlertTitle>{$_("proxy.auth.password.error.title")}</AlertTitle>
                    <AlertDescription>{$_("proxy.auth.password.error.description")}</AlertDescription>
                </Alert>
            {/if}

            <FieldSet class="mt-4 mb-2">
                <Field>
                    <Label for="password">{$_("proxy.auth.password.field.label")}</Label>
                    <Input
                            id="password"
                            type="password"
                            placeholder={$_("proxy.auth.password.field.placeholder")}
                            bind:value={passwordValue}
                            bind:ref={passwordInput}
                            aria-invalid={passwordErrors.length > 0 ? "true" : undefined}
                            onkeydown={e => { if (e.key === "Enter") handleSubmit(); }}
                    />
                    <FieldDescription>{$_("proxy.auth.password.field.description")}</FieldDescription>
                    <FieldError errors={passwordErrors} />
                </Field>
            </FieldSet>

            <Button
                    variant="default"
                    class="w-full"
                    disabled={currentState === "loading"}
                    onclick={handleSubmit}
            >
                {#if currentState === "loading"}
                    <div class="aspect-square w-4 h-4 animate-spin" transition:slide={{ duration: 100, axis: "x" }}>
                        <Loader />
                    </div>
                {/if}
                {$_("proxy.auth.password.button.continue")}
            </Button>

            <div class="h-2"></div>

            <FieldSeparator>{$_("proxy.auth.password.field_separator")}</FieldSeparator>

            <div class="h-2"></div>

            <Button variant="outline">{$_("proxy.auth.password.button.wbcloud")}</Button>
        </CardHeader>
    </Card>
</div>