import type {ColumnDef} from "@tanstack/table-core";
import type {Component} from "svelte";
import type {AccessKey} from "./getAccessKeys.ts";
import {renderComponent} from "$lib/components/ui/data-table";
import AccessKeyCreatedAtCell from "./table/AccessKeyCreatedAtCell.svelte";
import AccessKeyActionsCell from "./table/AccessKeyActionsCell.svelte";
import DataTableCheckbox from "../../projects/table/DataTableCheckbox.svelte";

export const columns = (config: {
    onDeleteKey: (id: string) => void,
    nameHeader: string,
    createdAtHeader: string,
}): ColumnDef<AccessKey>[] => [
    {
        accessorKey: "selection",
        meta: { compact: true },
        header: ((context) => {
            return renderComponent(
                DataTableCheckbox,
                {
                    checked: context.table.getIsAllPageRowsSelected(),
                    onCheckedChange: (checked: boolean) => {
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
                    onCheckedChange: (checked: boolean) => {
                        row.toggleSelected(checked)
                    }
                }
            )
        },
    },
    {
        accessorKey: "name",
        header: config.nameHeader,
    },
    {
        accessorKey: "created_at",
        header: config.createdAtHeader,
        cell: ({row}) => {
            return renderComponent(
                AccessKeyCreatedAtCell as Component<{accessKey: AccessKey}>,
                {accessKey: row.original}
            )
        }
    },
    {
        accessorKey: "actions",
        header: "",
        meta: { compact: true },
        cell: ({row}) => {
            return renderComponent(
                AccessKeyActionsCell as Component<{accessKey: AccessKey, onDelete: (id: string) => void}>,
                {
                    accessKey: row.original,
                    onDelete: config.onDeleteKey,
                }
            )
        }
    }
]
