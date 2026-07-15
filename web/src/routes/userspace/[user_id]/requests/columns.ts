import type {ColumnDef} from "@tanstack/table-core";
import type {RequestUpdate} from "../state.ts";
import {renderComponent} from "$lib/components/ui/data-table";

import DataTableProjectCellCell from "./table/DataTableProjectCell.svelte";
import type {Component} from "svelte";
import DataTableUrlCell from "./table/DataTableUrlCell.svelte";
import {unwrapFunctionStore, _} from "svelte-i18n";

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
    }
]