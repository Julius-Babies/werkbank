<script lang="ts">
    import {Popover, PopoverContent, PopoverDescription, PopoverTitle, PopoverTrigger} from "$lib/components/ui/popover";
    import type {Snippet} from "svelte";

    let {
        children,
    }: {
        children: Snippet;
    } = $props();
</script>

<Popover>
    <PopoverTrigger>
        {@render children()}
    </PopoverTrigger>
    <PopoverContent>
        <PopoverTitle>Create a new project</PopoverTitle>
        <PopoverDescription>
            Run the <code>wb setup</code> command in the directory of your project. It needs to have a Werkbankfile.yaml.
        </PopoverDescription>
    </PopoverContent>
</Popover>