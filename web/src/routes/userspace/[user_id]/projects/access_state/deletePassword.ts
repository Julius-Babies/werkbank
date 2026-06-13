export async function deletePasswordForProject(projectId: string, passwordId: string) {
    const response = await fetch(`/api/projects/${projectId}/access/password/${passwordId}`, {
        method: "DELETE"
    });

    return response.status === 200;
}