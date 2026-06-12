import type {ColumnDef} from "@tanstack/table-core";
import type {Component} from "svelte";
import type {Project} from "./getProjects.ts";
import {renderComponent} from "$lib/components/ui/data-table";
import ProjectKeyCell from "./table/ProjectKeyCell.svelte";
import ProjectCreatedAtCell from "./table/ProjectCreatedAtCell.svelte";
import DataTableCheckbox from "./table/DataTableCheckbox.svelte";

export const columns: ColumnDef<Project>[] = [
    {
        accessorKey: "selection",
        meta: { compact: true },
        header: ((context) => {
            return renderComponent(
                DataTableCheckbox,
                {
                    checked: context.table.getIsAllPageRowsSelected(),
                    onCheckedChange: (checked) => {
                        context.table.toggleAllPageRowsSelected(checked)
                    }
                }
            )
        }),
        cell: ({row}) => {
            return renderComponent(
                DataTableCheckbox,
                {
                    checked: row.getIsSelected(),
                    onCheckedChange: (checked) => {
                        row.toggleSelected(checked)
                    }
                }
            )
        },
    },
    {
        accessorKey: "project_key",
        header: "PROJECT",
        cell: ({row}) => {
            return renderComponent(
                ProjectKeyCell as Component<{project: Project}>,
                {project: row.original}
            )
        },
    },
    {
        accessorKey: "created_at",
        header: "CREATED AT",
        cell: ({row}) => {
            return renderComponent(
                ProjectCreatedAtCell as Component<{project: Project}>,
                {project: row.original}
            )
        }
    },
]