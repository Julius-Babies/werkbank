<script lang="ts">
    import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from "$lib/components/ui/dialog";
    import {DialogFooter} from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {Field, FieldError, FieldSet, Label} from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {EyeIcon, ClipboardTextIcon} from "phosphor-svelte";
    import {createAccessKey} from "./createAccessKey.ts";
    import { slide } from "svelte/transition";
    import { Loader } from "@lucide/svelte";
    import {_} from "svelte-i18n";

    let {
        onclose,
    }: {
        onclose: (reload: boolean) => void;
    } = $props();

    let name = $state("");
    let nameErrors: { message?: string }[] = $state([]);

    let showKey = $state(false);

    let creationState: "ready" | "loading" | "error" = $state("ready");

    async function createKey() {
        if (creationState === "loading") return;

        if (name.length < 3) {
            nameErrors = [{ message: $_("userspace.settings.access_keys.new_dialog.field.name.error") }];
            return;
        }
        nameErrors = [];
        creationState = "loading";
        try {
            const keyResult = await createAccessKey(name);
            if (keyResult) {
                key = keyResult;
                creationState = "ready";
            }
            else creationState = "error";
        } catch (e) {
            creationState = "error";
        }
    }

    let key: string | null = $state(null);
</script>

<Dialog open={true} onOpenChangeComplete={open => { if (!open) onclose(false) }}>
    <DialogContent>
        {#if !key}
            <DialogHeader>
                <DialogTitle>{$_("userspace.settings.access_keys.new_dialog.title")}</DialogTitle>
                <DialogDescription>{$_("userspace.settings.access_keys.new_dialog.description")}</DialogDescription>
            </DialogHeader>

            <div class="flex flex-col gap-4">
                <FieldSet>
                    <Field>
                        <Label for="name">{$_("userspace.settings.access_keys.new_dialog.field.name.label")}</Label>
                        <Input type="text" id="name" placeholder={$_("userspace.settings.access_keys.new_dialog.field.name.placeholder")} bind:value={name} aria-invalid={nameErrors.length > 0 ? "true" : undefined} onkeydown={e => { if (e.key === "Enter") createKey(); }}/>
                        <FieldError errors={nameErrors} />
                    </Field>
                </FieldSet>
            </div>

            <DialogFooter>
                <Button variant="outline" onclick={() => onclose(false)}>{$_("userspace.settings.access_keys.new_dialog.button.cancel")}</Button>
                <Button
                        variant="default"
                        disabled={creationState === "loading"}
                        onclick={() => createKey()}
                >
                    {#if creationState === "loading"}
                        <div class="aspect-square w-4 h-4 animate-spin" transition:slide={{ duration: 100, axis: "x" }}>
                            <Loader />
                        </div>
                    {/if}
                    {$_("userspace.settings.access_keys.new_dialog.button.save")}
                </Button>
            </DialogFooter>
        {:else}
            <DialogHeader>
                <DialogTitle>{$_("userspace.settings.access_keys.new_dialog.created.title")}</DialogTitle>
                <DialogDescription>{$_("userspace.settings.access_keys.new_dialog.created.description")}</DialogDescription>
            </DialogHeader>

            <div class="flex flex-col gap-4">
                <FieldSet>
                    <Field>
                        <Label for="key">{$_("userspace.settings.access_keys.new_dialog.created.field.key.label")}</Label>
                        <div class="flex flex-row items-center gap-1">
                            <Input type={showKey ? "text" : "password"} id="key" value={key} readonly inert/>
                            <Button variant="outline" size="icon" onclick={() => showKey = !showKey}><EyeIcon /></Button>
                        </div>
                    </Field>
                </FieldSet>
            </div>

            <DialogFooter>
                <Button onclick={() =>
                    navigator.clipboard.writeText(key!).then(() => {
                        onclose(true);
                    })
                 }>
                    <ClipboardTextIcon class="h-4 w-4" />
                    {$_("userspace.settings.access_keys.new_dialog.created.button.copy_close")}
                </Button>
            </DialogFooter>
        {/if}
    </DialogContent>
</Dialog>