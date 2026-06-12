<script lang="ts">
    import {title} from "../state.ts";
    import {Button} from "$lib/components/ui/button";
    import {Loader, PlusIcon} from "@lucide/svelte";
    import {Popover} from "$lib/components/ui/popover";
    import {_} from 'svelte-i18n'
    import {
        PopoverContent,
        PopoverDescription,
        PopoverTitle,
        PopoverTrigger
    } from "$lib/components/ui/popover/index.ts";
    import {getProjects, type Project} from "./getProjects.ts";
    import {onMount} from "svelte";
    import {getCoreRowModel} from "@tanstack/table-core";
    import {createSvelteTable, FlexRender} from "$lib/components/ui/data-table";
    import {columns} from "./columns.ts";
    import {Table, TableBody, TableCell, TableRow} from "$lib/components/ui/table";
    import {TableHead, TableHeader} from "$lib/components/ui/table/index.ts";

    $effect(() => {
        title.set($_("userspace.projects.title"))
    })

    let projects: Project[] | "loading" = $state("loading");

    onMount(() => {
        getProjects()
            .then(result => projects = result)
    })

    const table = $derived(projects === "loading" ? null : createSvelteTable({
        get data() {
            return projects as Project[];
        },
        columns,
        getCoreRowModel: getCoreRowModel(),
    }));
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

    <div>
        {#if projects === "loading"}
            <div class="flex flex-row gap-2 items-center w-full px-4 py-6 justify-center">
                <div class="animate-spin aspect-square w-6 h-6"><Loader /></div>
                <span>{$_("userspace.projects.your-projects.list.loading")}</span>
            </div>
        {:else}
            <Table>
                <TableHeader>
                    {#each table?.getHeaderGroups() as headerGroup (headerGroup.id)}
                        <TableRow>
                            {#each headerGroup.headers as header (header.id)}
                                <TableHead colspan={header.colSpan}>
                                    {#if !header.isPlaceholder}
                                        <FlexRender
                                                content={header.column.columnDef.header}
                                                context={header.getContext()}
                                        />
                                    {/if}
                                </TableHead>
                            {/each}
                        </TableRow>
                    {/each}
                </TableHeader>

                <TableBody>
                    {#each table?.getRowModel().rows as row (row.id)}
                        <TableRow data-state={row.getIsSelected() && "selected"}>
                            {#each row.getVisibleCells() as cell (cell.id)}
                                <TableCell>
                                    <FlexRender
                                            content={cell.column.columnDef.cell}
                                            context={cell.getContext()}
                                    />
                                </TableCell>
                            {/each}
                        </TableRow>
                    {:else}
                        <Table.Row>
                            <Table.Cell colspan={columns.length} class="h-24 text-center">
                                No results.
                            </Table.Cell>
                        </Table.Row>
                    {/each}
                </TableBody>
            </Table>
        {/if}
    </div>
</div>