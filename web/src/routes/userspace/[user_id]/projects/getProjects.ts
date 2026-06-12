export interface Project {
    project_id: string;
    project_key: string;
    project_name: string;
    created_at: Date
}

export async function getProjects(): Promise<Project[]> {
    const response = await fetch("/api/webapp/projects");
    const json = await response.json();
    return json.map((project: any) => ({
        project_id: project.project_id,
        project_key: project.project_key,
        project_name: project.project_name,
        created_at: new Date(project.created_at)
    }))
}