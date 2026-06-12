<script lang="ts">
    import {Checkbox} from "$lib/components/ui/checkbox";

    let {
        checked,
        onCheckedChange,
    }: {
        checked: boolean;
        onCheckedChange: (checked: boolean) => void;
    } = $props();
</script>

<Checkbox
        bind:checked={
            () => checked,
            (value) => onCheckedChange(value)
        }
/>