import type {ColumnDef} from "@tanstack/table-core";
import type {Component} from "svelte";
import type {Project} from "./getProjects.ts";
import {renderComponent} from "$lib/components/ui/data-table";
import ProjectKeyCell from "./table/ProjectKeyCell.svelte";
import ProjectCreatedAtCell from "./table/ProjectCreatedAtCell.svelte";
import DataTableCheckbox from "./table/DataTableCheckbox.svelte";
import ProjectActionsCell from "./table/ProjectActionsCell.svelte";
import ProjectAccessStateCell from "./table/access_state/ProjectAccessStateCell.svelte";

export const columns = (config: {
        onReloadProjects: () => void,
        onOpenAccessSettingsForProject: (projectId: string) => void,
    }): ColumnDef<Project>[] => [
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
        meta: { compact: true },
        cell: ({row}) => {
            return renderComponent(
                ProjectCreatedAtCell as Component<{project: Project}>,
                {project: row.original}
            )
        }
    },
    {
        accessorKey: "visibility",
        header: "VISIBILITY",
        meta: { compact: true },
        cell: ({row}) => {
            return renderComponent(
                ProjectAccessStateCell as Component<{project: Project}>,
                {
                    project: row.original,
                    onReloadProjects: config.onReloadProjects,
                    onOpenAccessSettingsForProject: config.onOpenAccessSettingsForProject,
                }
            )
        }
    },
    {
        accessorKey: "actions",
        header: "",
        meta: { compact: true },
        cell: ({row}) => {
            return renderComponent(
                ProjectActionsCell as Component<{project: Project}>,
                {
                    project: row.original
                }
            )
        }
    }
]
