<script lang="ts">
    import { Button } from "$lib/components/ui/button";
    import {TrashIcon} from "phosphor-svelte";
    import {formatDistanceToNow, type Locale} from "date-fns";
    import {onMount} from "svelte";
    import { de, enUS, fr, es } from 'date-fns/locale';
    import DeletePasswordDialog from "./DeletePasswordDialog.svelte";

    let {
        projectId,
        passwordId,
        label,
        createdAt,
        onReload,
    }: {
        projectId: string,
        passwordId: string,
        label: string,
        createdAt: number,
        onReload: () => void,
    } = $props();

    const localeMap: Record<string, Locale> = {
        'de': de,
        'en': enUS,
        'fr': fr,
        'es': es
    };
    let activeLocale = $state(enUS);


    let createdAtDate = $derived(new Date(createdAt));
    let createdAtString = $derived.by(() => {
        minuteTimer;
        return formatDistanceToNow(createdAtDate, { addSuffix: true, locale: activeLocale })
    });

    let minuteTimer = $state(1);

    onMount(() => {
        const browserLang = navigator.language;

        const shortLang = browserLang.substring(0, 2);

        if (localeMap[shortLang]) {
            activeLocale = localeMap[shortLang];
        }

        const interval = setInterval(() => {
            minuteTimer += 1;
        }, 60000);

        return () => clearInterval(interval);
    })

    let showDeleteDialog = $state(false);
</script>

<div class="border-b flex flex-row items-center justify-between p-2 gap-2 overflow-hidden">
    <div class="flex flex-col items-start pl-1">
        <span>{label}</span>
        <span class="text-xs text-neutral-600 font-medium">{createdAtString}</span>
    </div>
    <Button variant="destructive" size="icon" onclick={() => showDeleteDialog = true}>
        <TrashIcon />
    </Button>
</div>

{#if showDeleteDialog}
    <DeletePasswordDialog
            projectId={projectId}
            passwordId={passwordId}
            label={label}
            onClose={(success) => {
                showDeleteDialog = false
                if (success) onReload()
            }}
    />
{/if}