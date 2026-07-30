/**
 * Access to the generic project REST API (`/api/projects`), the same API the CLI talks to.
 */
export const ProjectRepository = {
    /** Deletes one of the current user's own projects; foreign or unknown projects answer with 404. */
    async deleteProject(projectId: string): Promise<boolean> {
        const response = await fetch(`/api/projects/${encodeURIComponent(projectId)}`, {
            method: "DELETE"
        });

        return response.ok;
    }
};
