<script lang="ts">
    import {Avatar, AvatarFallback, AvatarImage} from "$lib/components/ui/avatar";

    let {
        projectId,
        ownerProfileIcon,
        ownerUsername,
    }: {
        projectId: string,
        ownerProfileIcon: string,
        ownerUsername: string,
    } = $props();
</script>

<div class="relative self-center pb-4">
    <img src={"/api/projects/" + projectId + "/icon"} alt="Project Icon" class="size-12 rounded-md"/>
    <Avatar class="size-8 rounded-full absolute top-8 -right-4 border-4 border-background">
        <AvatarImage src={ownerProfileIcon} alt={ownerUsername}/>
        <AvatarFallback class="rounded-lg">{ownerUsername}</AvatarFallback>
    </Avatar>
</div>