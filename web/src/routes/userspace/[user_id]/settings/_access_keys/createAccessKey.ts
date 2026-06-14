export async function createAccessKey(name: string) {
    const result = await fetch("/api/webapp/settings/access-keys", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            name: name
        })
    })

    if (result.status !== 200) return null

    const data = await result.json()
    return data.access_key
}