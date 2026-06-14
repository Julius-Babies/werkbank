export async function deleteAccessKey(id: string): Promise<boolean> {
    const response = await fetch(`/api/webapp/settings/access-keys/${id}`, { method: "DELETE" });
    return response.status === 200;
}
