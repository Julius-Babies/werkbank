import type {RequestUpdate} from "../../state.ts";

export interface Request {
    request_id: string;
    project: {
        project_id: string;
        project_name: string;
    }
    service: {
        service_key: string | null;
    }
    request: {
        headers: Record<string, string[]>;
        method: string;
        uri: string;
    }
    response: {
        type: "success";
        status_code: number;
        headers: Record<string, string[]>;
    } | {
        type: "error";
        error: string;
    } | null;
}

export function fromRequestUpdate(requestUpdate: RequestUpdate): Request | null {
    if (requestUpdate.target === null) return null;
    return  {
        request_id: requestUpdate.request_id,
        project: {
            project_id: requestUpdate.target.project_id,
            project_name: requestUpdate.target.project_name
        },
        service: {
            service_key: requestUpdate.target.service_name,
        },
        request: {
            headers: {},
            method: requestUpdate.method,
            uri: requestUpdate.uri
        },
        response: requestUpdate.status_code ? {
            type: "success",
            status_code: requestUpdate.status_code,
            headers: {},
        } : requestUpdate.error ? {
            type: "error",
            error: requestUpdate.error,
        } : null,
    }
}

export async function getRequest(requestId: string): Promise<{ request: Request, requestBodySize: number, responseBodySize: number } | null> {
    const response = await fetch(`/api/webapp/requests/${requestId}`);
    if (!response.ok) return null;

    const data = await response.json() as {
        request_id: string;
        method: string;
        uri: string;
        target: {
            project_id: string;
            project_name: string;
            service_id: string;
            service_name: string;
        };
        request_headers: Record<string, string[]>;
        response_headers: Record<string, string[]>;
        response_status_code: number | null;
        response_error: string | null;
        request_body_size: number;
        response_body_size: number;
    };

    return {
        request: {
            request_id: data.request_id,
            project: {
                project_id: data.target.project_id,
                project_name: data.target.project_name,
            },
            service: {
                service_key: data.target.service_name,
            },
            request: {
                headers: data.request_headers,
                method: data.method,
                uri: data.uri,
            },
            response: data.response_status_code ? {
                type: "success",
                status_code: data.response_status_code,
                headers: data.response_headers,
            } : data.response_error ? {
                type: "error",
                error: data.response_error,
            } : null,
        },
        requestBodySize: data.request_body_size,
        responseBodySize: data.response_body_size
    };
}