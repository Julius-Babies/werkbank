export const methodColors = {
    GET: "text-green-600",
    POST: "text-blue-600",
    PUT: "text-amber-600",
    PATCH: "text-purple-600",
    DELETE: "text-red-600",
    HEAD: "text-slate-600",
    OPTIONS: "text-cyan-600",
    TRACE: "text-pink-600",
    CONNECT: "text-indigo-600",
} as const;

export function statusColor(status: number): string {
    if (status >= 100 && status < 200) return "text-sky-600";     // Informational
    if (status >= 200 && status < 300) return "text-green-600";   // Success
    if (status >= 300 && status < 400) return "text-amber-600";   // Redirect
    if (status >= 400 && status < 500) return "text-orange-600";  // Client Error
    if (status >= 500 && status < 600) return "text-red-600";     // Server Error

    return "text-slate-600";
}