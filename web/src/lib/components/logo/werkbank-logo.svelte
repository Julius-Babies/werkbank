<script module lang="ts">
    export type WerkbankLogoColor = "blue" | "green" | "red";
    export type WerkbankLogoState = "hidden" | "visible" | "loading";
    /** Direction the bars travel when leaving. "down" retraces the way they entered. */
    export type WerkbankLogoExit = "down" | "up";
</script>

<script lang="ts">
    import { cn } from "$lib/utils.js";
    import { untrack } from "svelte";
    import type { SVGAttributes } from "svelte/elements";

    type Props = SVGAttributes<SVGSVGElement> & {
        color?: WerkbankLogoColor;
        state?: WerkbankLogoState;
        exit?: WerkbankLogoExit;
        /** Render the entry animation on mount instead of showing `state` right away. */
        animateOnMount?: boolean;
        class?: string;
    };

    let {
        color = "blue",
        // Renamed locally: a binding called `state` would make the `$state` rune below
        // parse as a store subscription.
        state: logoState = "visible",
        exit = "down",
        animateOnMount = false,
        class: className,
        ...restProps
    }: Props = $props();

    // Same geometry as the logo_*.svg assets: the bars span x 6.666..126, y 0..99.97 and
    // are centred in a 133x133 box, which leaves ~16.5 units of headroom on every side for
    // the loading wave to move into.
    const BARS = [
        "M28.6663 0V0C44.1303 0 53.2109 12.0504 48.9484 26.9153L28 99.9712V99.9712C12.536 99.9712 3.4554 87.9208 7.71785 73.0559L28.6663 0Z",
        "M66.6663 0V0C82.1303 0 91.2109 12.0504 86.9484 26.9153L66 99.9712V99.9712C50.536 99.9712 41.4554 87.9208 45.7178 73.0559L66.6663 0Z",
        "M104.666 0V0C120.13 0 129.211 12.0504 124.948 26.9153L104 99.9712V99.9712C88.536 99.9712 79.4554 87.9208 83.7178 73.0559L104.666 0Z",
    ];
    const CONTENT_OFFSET = "translate(0.167 16.514)";

    const GRADIENT_STOPS: Record<WerkbankLogoColor, readonly [string, string]> = {
        blue: ["#6685C5", "#32405F"],
        green: ["#66C586", "#325F41"],
        red: ["#C56666", "#5F3232"],
    };

    const ENTER_MS = 620;
    const EXIT_MS = 520;
    // Opacity is deliberately quicker than the movement, so the round bar caps have already
    // faded by the time the viewBox clips them square at the edge.
    const ENTER_FADE_MS = 320;
    const EXIT_FADE_MS = 300;
    const SETTLE_MS = 260;
    const REDUCED_FADE_MS = 200;
    const STAGGER_MS = 80;

    /**
     * Marks a bar as part of the loading wave. The wave is a CSS animation (see the style block)
     * and not a WAAPI one, so it runs on markup alone: the proxy error pages are fetched by the
     * API and re-served from the project subdomain, where the hydration bundle is cross-origin
     * and never loads. A JS-driven wave stands still on exactly the pages whose job it is to show
     * that something is still being waited on.
     */
    const WAVE_CLASS = "wb-logo-wave-bar";

    const ENTER_EASING = "cubic-bezier(0.16, 0.84, 0.32, 1)";
    const EXIT_EASING = "cubic-bezier(0.55, 0, 0.85, 0.35)";

    type Pose = { opacity: string; transform: string };

    const AT_REST: Pose = { opacity: "1", transform: "translate(0px, 0px)" };
    // The bars move along their own slant (-0.2868 x per 1 y) over the full bar height, so
    // they read as being drawn rather than sliding sideways across the canvas.
    const OFFSCREEN: Record<WerkbankLogoExit, Pose> = {
        down: { opacity: "0", transform: "translate(-28.68px, 100px)" },
        up: { opacity: "0", transform: "translate(28.68px, -100px)" },
    };

    // `$props.id()` has to be a plain declaration initializer, hence the two steps.
    const uid = $props.id();
    const gradientId = `wb-logo-${uid}`;
    const stops = $derived(GRADIENT_STOPS[color]);

    function poseFor(target: WerkbankLogoState, direction: WerkbankLogoExit): Pose {
        return target === "hidden" ? OFFSCREEN[direction] : AT_REST;
    }

    // Captured once so the server-rendered markup and the first paint already show the
    // initial state; every later change is driven imperatively from the effect below.
    const initialState = untrack(() => (animateOnMount ? "hidden" : logoState));
    const initialPose = untrack(() => poseFor(initialState, exit));
    const initialStyle = `opacity: ${initialPose.opacity}; transform: ${initialPose.transform}`;
    const initialClass = initialState === "loading" ? WAVE_CLASS : "";

    let paths = $state<SVGPathElement[]>([]);

    // Intentionally not reactive: these only record what the DOM is currently doing.
    let waving = initialState === "loading";
    let waveTimer: ReturnType<typeof setTimeout> | undefined;
    let applied: WerkbankLogoState | undefined;
    /** When the transition that is currently running has every bar back at rest. */
    let restingAt = 0;

    $effect(() => {
        const target = logoState;
        const direction = exit;

        if (paths.length < BARS.length) return;

        const previous = applied;
        applied = target;
        transitionTo(target, direction, previous);
    });

    $effect(() => () => stopWave());

    function transitionTo(
        target: WerkbankLogoState,
        direction: WerkbankLogoExit,
        previous: WerkbankLogoState | undefined,
    ) {
        clearTimeout(waveTimer);
        waveTimer = undefined;

        const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

        // First run without animateOnMount: the markup already renders the right pose, and a
        // loading one is already waving from the server-rendered class - startWave() only adopts
        // it. Decided before the freeze below, which would otherwise cancel that wave just to
        // restart it, one hitch into hydration.
        if (previous === undefined && !animateOnMount) {
            restingAt = performance.now();
            if (target === "loading") startWave();
            return;
        }

        const wasWaving = waving;
        // Freeze the pose the wave is currently showing, otherwise cancelling it snaps the
        // bars back to their base style and the next move starts from the wrong place.
        if (wasWaving) freezeWave();

        if (target === "hidden") {
            // Leaving downwards runs right-to-left so it does not look like the entry
            // played backwards; leaving upwards keeps the entry order.
            const order = direction === "down" ? [2, 1, 0] : [0, 1, 2];
            move(OFFSCREEN[direction], order, EXIT_MS, EXIT_FADE_MS, EXIT_EASING, reduced);
            return;
        }

        if (wasWaving) {
            // Coming out of the wave the bars sit at three different phases, so a stagger
            // on top of that reads as a stumble - settle them together instead.
            move(AT_REST, [0, 1, 2], SETTLE_MS, SETTLE_MS, "ease-out", reduced, 0);
            return;
        }

        if (previous === "visible" && target === "loading") {
            // The wave's first keyframe *is* the resting pose, so it can take over without a
            // jump - but only once the bars actually got there. Flipping states faster than
            // a transition runs would otherwise let the wave override it mid-flight.
            scheduleWave(Math.max(0, restingAt - performance.now()));
            return;
        }

        move(AT_REST, [0, 1, 2], ENTER_MS, ENTER_FADE_MS, ENTER_EASING, reduced);
        if (target === "loading") {
            // Let the bars arrive first, for the same reason as above.
            scheduleWave(restingAt - performance.now());
        }
    }

    function scheduleWave(delayMs: number) {
        if (delayMs <= 0) {
            startWave();
            return;
        }
        waveTimer = setTimeout(() => startWave(), delayMs);
    }

    /**
     * Transitions every bar to `pose`, staggered in the given bar order. Under reduced
     * motion the movement is dropped entirely and only the opacity changes.
     */
    function move(
        pose: Pose,
        order: number[],
        moveMs: number,
        fadeMs: number,
        easing: string,
        reduced: boolean,
        staggerMs = STAGGER_MS,
    ) {
        const target = reduced ? { opacity: pose.opacity, transform: AT_REST.transform } : pose;
        const longest = reduced
            ? REDUCED_FADE_MS
            : (order.length - 1) * staggerMs + Math.max(moveMs, fadeMs);
        restingAt = performance.now() + longest;

        order.forEach((bar, position) => {
            const element = paths[bar];
            element.style.transitionProperty = "opacity, transform";
            element.style.transitionDuration = reduced
                ? `${REDUCED_FADE_MS}ms, 0ms`
                : `${fadeMs}ms, ${moveMs}ms`;
            element.style.transitionTimingFunction = `${easing}, ${easing}`;
            element.style.transitionDelay = reduced ? "0ms, 0ms" : `${position * staggerMs}ms`;
            element.style.opacity = target.opacity;
            element.style.transform = target.transform;
        });
    }

    function startWave() {
        // Keyframe 0 is the resting pose, so a bar that already sits there enters the loop
        // without a jump - which is also why the phase offsets are positive delays here, unlike
        // in the standalone logo_*_loading.svg assets. Adding a class that is already on the
        // element is a no-op, so a wave carried over from the server-rendered markup keeps
        // running instead of restarting on hydration.
        for (const element of paths) element.classList.add(WAVE_CLASS);
        waving = true;
    }

    /** Writes the wave's current pose into the inline style, then cancels it. */
    function freezeWave() {
        const frozen = paths.map((element) => {
            const computed = getComputedStyle(element);
            return { opacity: computed.opacity, transform: computed.transform };
        });

        stopWave();

        paths.forEach((element, index) => {
            element.style.transitionProperty = "none";
            element.style.opacity = frozen[index].opacity;
            element.style.transform = frozen[index].transform;
        });

        // Force a style flush so the frozen pose becomes the start value of the transition
        // that the caller sets up next.
        paths[0]?.getBoundingClientRect();
    }

    function stopWave() {
        clearTimeout(waveTimer);
        waveTimer = undefined;
        for (const element of paths) element.classList.remove(WAVE_CLASS);
        waving = false;
    }
</script>

<svg
    width="133"
    height="133"
    viewBox="0 0 133 133"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    class={cn("size-6", className)}
    {...restProps}
>
    <defs>
        <linearGradient
            id={gradientId}
            gradientUnits="userSpaceOnUse"
            x1="28.6663"
            y1="0"
            x2="104"
            y2="99.9712"
        >
            <stop style="stop-color: {stops[0]}" />
            <stop offset="1" style="stop-color: {stops[1]}" />
        </linearGradient>
    </defs>

    <g transform={CONTENT_OFFSET}>
        {#each BARS as bar, index (index)}
            <path
                bind:this={paths[index]}
                d={bar}
                fill="url(#{gradientId})"
                class={initialClass}
                style={initialStyle}
            />
        {/each}
    </g>
</svg>

<style>
    /* Colour swaps interpolate instead of cutting, so a state change that also changes
       colour (green -> red) still reads as one continuous logo. */
    stop {
        transition: stop-color 320ms ease;
    }

    /* :global, because the class is toggled imperatively: a render that starts out not
       loading would not mention it, and Svelte prunes scoped selectors it cannot see in the
       markup. The wb-logo- prefix keeps the unscoped names from colliding.

       Animations outrank the inline opacity/transform that the JS transitions write, so the
       wave takes over a bar without those having to be cleared first - and stopWave()'s
       freeze step is what puts the wave's last pose back into the inline style. */
    :global(.wb-logo-wave-bar) {
        animation: wb-logo-wave 1100ms ease-in-out infinite;
    }

    /* Offsets by DOM position rather than a per-bar inline delay, so the SSR markup needs
       nothing but the shared class. The <g> holds only the three bars. */
    :global(.wb-logo-wave-bar:nth-child(2)) {
        animation-delay: 130ms;
    }

    :global(.wb-logo-wave-bar:nth-child(3)) {
        animation-delay: 260ms;
    }

    @keyframes wb-logo-wave {
        0%,
        100% {
            opacity: 1;
            transform: translate(0px, 0px);
        }
        50% {
            opacity: 0.28;
            transform: translate(-1.15px, 4px);
        }
    }

    /* Still has to signal "busy", so the movement is dropped and only a slow, synchronised
       pulse remains. */
    @media (prefers-reduced-motion: reduce) {
        :global(.wb-logo-wave-bar),
        :global(.wb-logo-wave-bar:nth-child(2)),
        :global(.wb-logo-wave-bar:nth-child(3)) {
            animation: wb-logo-wave-reduced 1800ms ease-in-out infinite;
            animation-delay: 0ms;
        }
    }

    @keyframes wb-logo-wave-reduced {
        0%,
        100% {
            opacity: 1;
        }
        50% {
            opacity: 0.45;
        }
    }
</style>
