export async function addExistingPasswordToProject(
    projectId: string,
    passwordId: string,
) {
    const response = await fetch(`/api/projects/${projectId}/access/password`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            password_id: passwordId,
        })
    });

    return response.status === 200;
}