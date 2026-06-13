export async function getPasswordOptions(projectId: string) {
    const response = await fetch(`/api/webapp/projects/${projectId}/access/passwords/options`);

    return await response.json() as Promise<PasswordOption[]>;
}

export interface PasswordOption {
    id: string;
    label: string;
}