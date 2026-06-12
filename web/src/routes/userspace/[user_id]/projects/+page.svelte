<script lang="ts">
    import {title} from "../state.ts";
    import {Button} from "$lib/components/ui/button";
    import {PlusIcon} from "@lucide/svelte";
    import {Popover} from "$lib/components/ui/popover";
    import {_} from 'svelte-i18n'
    import {
        PopoverContent,
        PopoverDescription,
        PopoverTitle,
        PopoverTrigger
    } from "$lib/components/ui/popover/index.ts";

    $effect(() => {
        title.set($_("userspace.projects.title"))
    })
</script>

<div class="flex flex-col overflow-y-auto p-8">
    <div class="flex flex-row justify-between w-full">
        <h1 class="text-2xl font-bold flex-1">{$_("userspace.projects.your-projects.title")}</h1>
        <Popover>
            <PopoverTrigger>
                <Button
                >
                    <PlusIcon />
                    {$_("userspace.projects.your-projects.create")}
                </Button>
            </PopoverTrigger>
            <PopoverContent>
                <PopoverTitle>Create a new project</PopoverTitle>
                <PopoverDescription>
                    Run the <code>wb setup</code> command in the directory of your project. It needs to have a Werkbankfile.yaml.
                </PopoverDescription>
            </PopoverContent>
        </Popover>
    </div>
</div>