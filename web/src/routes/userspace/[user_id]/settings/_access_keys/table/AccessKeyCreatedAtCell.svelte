<script lang="ts">
    import type {AccessKey} from "../getAccessKeys.ts";

    let {
        accessKey,
    }: {
        accessKey: AccessKey
    } = $props();

    const options: Intl.DateTimeFormatOptions = {
        day: 'numeric',
        month: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    };

    let date = $derived(new Date(accessKey.created_at).toLocaleDateString(undefined, options));
</script>

<div class="text-neutral-700">
    {date}
</div>
