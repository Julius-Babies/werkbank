export async function setAccessState(
    projectId: string,
    accessState: string
) {
    const response = await fetch(`/api/projects/${projectId}/access`, {
        method: "PUT",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            access_state: accessState
        })
    })

    return response.status === 200
}