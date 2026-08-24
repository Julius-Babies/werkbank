export const en = {
    userspace: {
        requests: {
            title: "Tunnel Requests",
            search_further: "Search further back",
            filter: {
                reset: "Reset filter",
                placeholder: "Filter requests, e.g. method:GET is:websocket",
                suggestions: "Filter suggestions",
                qualifiers: {
                    method: "Method",
                    is: "Kind",
                    status: "Status code",
                    project: "Project",
                    service: "Service",
                },
                values: {
                    http: "HTTP request",
                    websocket: "WebSocket connection",
                },
                projects: {
                    label: "Projects",
                    empty: "No projects yet",
                },
                advanced: "This query uses filters the buttons cannot represent. Reset the filter to use them again.",
            },
            table: {
                project: "PROJECTS",
                resource: "RESOURCE",
                result: "RESULT",
            },
            empty: {
                title: "No requests yet",
                description: "You haven't made any requests yet. Use the wb tunnel to get started.",
                install: "Install WB CLI",
                filtered: {
                    title: "No matching requests",
                    description: "{count, plural, one {# request is hidden by the current filter.} other {# requests are hidden by the current filter.}}",
                    clear: "Clear filter",
                },
            },
        },
    },
}

export const de = {
    userspace: {
        requests: {
            title: "Tunnel-Anfragen",
            search_further: "Weiter zurück suchen",
            filter: {
                reset: "Filter zurücksetzen",
                placeholder: "Anfragen filtern, z.B. method:GET is:websocket",
                suggestions: "Filter-Vorschläge",
                qualifiers: {
                    method: "Methode",
                    is: "Art",
                    status: "Statuscode",
                    project: "Projekt",
                    service: "Service",
                },
                values: {
                    http: "HTTP-Anfrage",
                    websocket: "WebSocket-Verbindung",
                },
                projects: {
                    label: "Projekte",
                    empty: "Noch keine Projekte",
                },
                advanced: "Diese Query nutzt Filter, die sich nicht über die Buttons darstellen lassen. Setze den Filter zurück, um sie wieder zu nutzen.",
            },
            table: {
                project: "PROJEKTE",
                resource: "RESSOURCE",
                result: "ERGEBNIS",
            },
            empty: {
                title: "Noch keine Anfragen",
                description: "Du hast noch keine Anfragen gestellt. Verwende den wb tunnel, um loszulegen.",
                install: "WB CLI installieren",
                filtered: {
                    title: "Keine passenden Anfragen",
                    description: "{count, plural, one {# Anfrage wird vom aktuellen Filter ausgeblendet.} other {# Anfragen werden vom aktuellen Filter ausgeblendet.}}",
                    clear: "Filter zurücksetzen",
                },
            },
        },
    },
}
