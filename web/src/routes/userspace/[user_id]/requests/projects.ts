import {writable} from "svelte/store";
import {getProjects, type Project} from "../projects/getProjects.ts";

/** `null` while the projects have not been loaded yet. */
export const projects = writable<Project[] | null>(null)

let pending: Promise<void> | null = null

/**
 * Loads the projects once, so the filter can suggest project keys. Kept out of the filter itself
 * because the suggestions are the only place that needs them, and only once they are opened.
 */
export function loadProjects(): Promise<void> {
    pending ??= getProjects()
        .then((loaded) => projects.set(loaded))
        .catch((error) => {
            console.error("Could not load projects for the filter suggestions", error)
            projects.set([])
        })

    return pending
}
