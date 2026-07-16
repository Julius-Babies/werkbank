import type {ColumnDef} from "@tanstack/table-core";
import type {RequestUpdate} from "../state.ts";
import {renderComponent} from "$lib/components/ui/data-table";

import DataTableProjectCellCell from "./table/DataTableProjectCell.svelte";
import type {Component} from "svelte";
import DataTableUrlCell from "./table/DataTableUrlCell.svelte";
import {unwrapFunctionStore, _} from "svelte-i18n";
import DataTableResultCell from "./table/DataTableResultCell.svelte";

const t = unwrapFunctionStore(_);

export const columns = (): ColumnDef<RequestUpdate>[] => [
    {
        accessorKey: "project",
        meta: {compact: true},
        header: t("userspace.requests.table.project"),
        cell: ({row}) => {
            return renderComponent(
                DataTableProjectCellCell as Component<{ request: RequestUpdate }>,
                {request: row.original}
            )
        }
    },
    {
        accessorKey: "uri",
        header: t("userspace.requests.table.resource"),
        cell: ({row}) => {
            return renderComponent(
                DataTableUrlCell as Component<{ request: RequestUpdate }>,
                {request: row.original}
            )
        }
    },
    {
        accessorKey: "result",
        header: "RESULT",
        meta: {compact: true},
        cell: ({row}) => {
            return renderComponent(
                DataTableResultCell as Component<{ request: RequestUpdate }>,
                {request: row.original}
            )
        }
    }
]