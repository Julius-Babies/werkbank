export type AccessKey = {
    id: string;
    name: string;
    created_at: number;
}

export async function getAccessKeys(): Promise<AccessKey[]> {
    const response = await fetch("/api/webapp/settings/access-keys");
    return await response.json();
}
