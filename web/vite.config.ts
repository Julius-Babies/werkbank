import tailwindcss from '@tailwindcss/vite';
import {sveltekit} from '@sveltejs/kit/vite';
import {defineConfig} from 'vite';

export default defineConfig({
    plugins: [tailwindcss(), sveltekit()],
    server: {
        allowedHosts: [
            "werkbank.werkbank.space",
            "julius-babies.werkbank.werkbank.space"
        ]
    }
});
