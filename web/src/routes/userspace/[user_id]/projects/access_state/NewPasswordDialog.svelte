<script lang="ts">
    import {
        Dialog,
        DialogContent,
        DialogDescription,
        DialogFooter,
        DialogHeader,
        DialogTitle
    } from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {Tabs, TabsList, TabsTrigger} from "$lib/components/ui/tabs";
    import {Field, FieldDescription, FieldError, FieldGroup, FieldSet, Label} from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {slide} from "svelte/transition";
    import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger} from "$lib/components/ui/select";
    import {_} from "svelte-i18n";
    import {Loader} from "@lucide/svelte";
    import {createNewPasswordForProject} from "./createNewPassword.ts";
    import {onMount} from "svelte";
    import {getPasswordOptions, type PasswordOption} from "./getPasswordOptions.ts";
    import {addExistingPasswordToProject} from "./addExistingPassword.ts";

    let {
        projectId,
        onClose,
    }: {
        projectId: string;
        onClose: (shouldReload: boolean) => void;
    } = $props();

    type PasswordType = "create_new" | "use_existing";
    let currentTab: PasswordType = $state("create_new");

    let labelValue = $state("");
    let passwordValue = $state("");

    let labelErrors: { message?: string }[] = $state([]);
    let passwordErrors: { message?: string }[] = $state([]);
    let selectErrors: { message?: string }[] = $state([]);

    let isLoading = $state(false);

    function validate(): boolean {
        labelErrors = [];
        passwordErrors = [];
        let valid = true;
        if (currentTab === "create_new") {
            if (labelValue.length < 3) {
                labelErrors = [{ message: $_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.label.error") }];
                valid = false;
            }

            if (passwordValue.length < 3) {
                passwordErrors = [{ message: $_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.password.error") }];
                valid = false;
            }
        } else if (currentTab === "use_existing") {
            if (!currentPasswordOption) {
                selectErrors = [{ message: $_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.use_existing.select.error") }];
                valid = false;
            }
        }

        return valid;
    }

    async function createNewPassword() {
        if (isLoading) return;
        isLoading = true;

        try {
            const success = await createNewPasswordForProject(projectId, labelValue, passwordValue);
            if (success) onClose(true);
        } finally {
            isLoading = false;
        }
    }

    async function useExistingPassword() {
        console.log("use existing password");
        if (isLoading) return;
        if (!currentPasswordOption) return;
        isLoading = true;
        try {
            const success = await addExistingPasswordToProject(projectId, currentPasswordOption.id);
            if (success) onClose(true);
        } finally {
            isLoading = false;
        }
    }

    async function submit() {
        if (!validate()) return;
        if (currentTab === "create_new") {
            await createNewPassword();
        } else if (currentTab === "use_existing") {
            await useExistingPassword();
        }
    }

    let options: PasswordOption[] = $state([]);
    let currentPasswordOption: PasswordOption | undefined = $state(undefined);

    onMount(() => {
        getPasswordOptions(projectId).then(result => options = result)
    })
</script>

<Dialog open={true} onOpenChange={open => { if (!open) onClose(false); }}>
    <DialogContent>
        <DialogHeader>
            <DialogTitle>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.title")}</DialogTitle>
            <DialogDescription>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.description")}</DialogDescription>
        </DialogHeader>

        <div class="flex flex-col">
            <Tabs value={currentTab} onValueChange={value => { currentTab = value as PasswordType; labelErrors = []; passwordErrors = []; selectErrors = []; currentPasswordOption = undefined }} class="mb-2">
                <TabsList>
                    <TabsTrigger
                            value="create_new">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.title")}</TabsTrigger>
                    <TabsTrigger value="use_existing">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.use_existing.title")}</TabsTrigger>
                </TabsList>
            </Tabs>

            {#if currentTab === "create_new"}
                <div class="flex flex-col gap-2" transition:slide={{ duration: 100, axis: "y" }}>
                    <FieldSet>
                        <FieldGroup>
                            <Field>
                                <Label for="label">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.label.title")}</Label>
                                <Input
                                        id="label"
                                        placeholder={$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.label.placeholder")}
                                        bind:value={labelValue}
                                        aria-invalid={labelErrors.length > 0 ? "true" : undefined}
                                />
                                <FieldDescription>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.label.description")}</FieldDescription>
                                <FieldError errors={labelErrors} />
                            </Field>
                        </FieldGroup>

                        <FieldGroup>
                            <Field>
                                <Label for="password">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.password.title")}</Label>
                                <Input
                                        id="password"
                                        placeholder={$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.password.placeholder")}
                                        type="password" bind:value={passwordValue}
                                        aria-invalid={passwordErrors.length > 0 ? "true" : undefined}
                                />
                                <FieldDescription>{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.create_new.password.description")}</FieldDescription>
                                <FieldError errors={passwordErrors} />
                            </Field>
                        </FieldGroup>
                    </FieldSet>
                </div>
            {:else if currentTab === "use_existing"}
                <div class="flex flex-col gap-2" transition:slide={{ duration: 100, axis: "y" }}>
                    <FieldSet>
                        <FieldGroup>
                            <Field>
                                <Label for="existing_password">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.use_existing.existing_password.title")}</Label>
                                <Select type="single" value={currentPasswordOption?.id} onValueChange={value => { currentPasswordOption = options.find(option => option.id === value); selectErrors = []; }}>
                                    <SelectTrigger aria-invalid={selectErrors.length > 0 ? "true" : undefined}>
                                        {#if currentPasswordOption}
                                            {currentPasswordOption.label}
                                        {:else}
                                            {$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.use_existing.select.placeholder")}
                                        {/if}
                                    </SelectTrigger>

                                    <SelectContent>
                                        <SelectGroup>
                                            {#each options as option (option.id)}
                                                <SelectItem value={option.id}>{option.label}</SelectItem>
                                            {/each}
                                        </SelectGroup>
                                    </SelectContent>
                                </Select>
                                <FieldError errors={selectErrors} />
                            </Field>
                        </FieldGroup>
                    </FieldSet>
                </div>
            {/if}
        </div>

        <DialogFooter>
            <Button variant="outline">{$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.footer.cancel")}</Button>
            <Button
                    variant="default"
                    disabled={isLoading}
                    onclick={submit}
            >
                {#if isLoading}
                    <div class="aspect-square w-4 h-4 animate-spin" transition:slide={{ duration: 100, axis: "x" }}>
                        <Loader />
                    </div>
                {/if}
                {#if currentTab === "create_new"}
                    {$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.footer.create_new")}
                {:else if currentTab === "use_existing"}
                    {$_("userspace.projects.project.access_settings.dialog.project_access.passwords.create_new_password.dialog.footer.use_existing")}
                {/if}
            </Button>
        </DialogFooter>
    </DialogContent>
</Dialog>