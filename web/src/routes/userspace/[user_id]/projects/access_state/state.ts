export interface ProjectAccessState {
    project_id: string;
    project_name: string;
    project_access_state: "disabled" | "restricted" | "open";
    project_passwords: {
        id: string;
        label: string;
        created_at: number;
    }[]
}

export async function getProjectAccessState(projectId: string): Promise<ProjectAccessState> {
    const response = await fetch(`/api/webapp/projects/${projectId}/access`);

    return response.json();
}