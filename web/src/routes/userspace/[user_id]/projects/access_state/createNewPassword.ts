export async function createNewPasswordForProject(
    projectId: string,
    label: string,
    newPassword: string,
) {
    const response = await fetch(`/api/projects/${projectId}/access/password/new`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            label: label,
            password: newPassword,
        })
    });

    return response.status === 200;
}