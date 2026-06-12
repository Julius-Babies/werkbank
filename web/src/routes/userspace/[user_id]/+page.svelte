<script lang="ts">
    import {onMount} from "svelte";
    import {title} from "./state.ts";
    import {Timeline, TimelineContent, TimelineIndicator, TimelineItem} from "$lib/components/ui/timeline/index.ts";
    import { _ } from 'svelte-i18n'

    onMount(() => {
        title.set("Home")
    })
</script>

<div class="flex flex-col overflow-y-auto p-4">
    <div class="flex justify-center items-center w-full mt-8">
        <h1 class="text-2xl font-bold">{$_("userspace.getting-started.title")}</h1>
    </div>

    <div class="max-w-2xl mx-auto mt-8">
        <Timeline>
            <TimelineItem>
                <TimelineIndicator>
                    1
                </TimelineIndicator>

                <TimelineContent>
                    <div class="inline-block">
                        Download the <code>wb cli</code> from <a href="https://github.com/Julius-Babies/werkbank/releases/latest" class="underline">GitHub Releases</a>.
                    </div>
                </TimelineContent>
            </TimelineItem>

            <TimelineItem>
                <TimelineIndicator>
                    2
                </TimelineIndicator>

                <TimelineContent>
                    <div class="inline-block">
                        Log into your WBCloud Account using the <code>wb login</code> command.
                    </div>
                </TimelineContent>
            </TimelineItem>

            <TimelineItem>
                <TimelineIndicator>
                    3
                </TimelineIndicator>

                <TimelineContent>
                    <div class="inline-block">
                        Create your first project using <code>wb setup</code>. If you already have a local Werkbank project, just re-run the command.
                    </div>
                </TimelineContent>
            </TimelineItem>

            <TimelineItem>
                <TimelineIndicator>
                    4
                </TimelineIndicator>

                <TimelineContent>
                    <div class="inline-block">
                        Start the Proxy using <code>wb tunnel</code>.
                    </div>
                </TimelineContent>
            </TimelineItem>

            <TimelineItem>
                <TimelineIndicator>
                    5
                </TimelineIndicator>

                <TimelineContent>
                    <div class="inline-block">
                        Manage access to your projects in <a href="/projects" class="underline">WB Cloud Project Manager</a>
                    </div>
                </TimelineContent>
            </TimelineItem>
        </Timeline>
    </div>

</div>