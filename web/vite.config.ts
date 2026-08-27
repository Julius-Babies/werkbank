import tailwindcss from '@tailwindcss/vite';
import {sveltekit} from '@sveltejs/kit/vite';
import {defineConfig} from 'vite';

/**
 * Base domains werkbank itself is served under. A project's own subdomain is one level deeper
 * (`<destination>.<user>.<base domain>`), which is where the API re-serves the /proxy/error pages
 * from - with a <base> pointing back here, so their assets are fetched cross-origin. Vite 6+ only
 * allows localhost origins by default, which would block the module imports the pages hydrate with.
 */
const WERKBANK_BASE_DOMAINS = [
    'werkbank.wb.local',
    'werkbank.wbdev.local',
    'wbcloud-dev-juliusbabies-midnight.dev.wbspace.app',
];

const werkbankOrigin = new RegExp(
    `^https?://([a-z0-9-]+\\.)*(${WERKBANK_BASE_DOMAINS.map((domain) => domain.replaceAll('.', '\\.')).join('|')})$`,
);
const loopbackOrigin = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/;

export default defineConfig({
    plugins: [tailwindcss(), sveltekit()],
    server: {
        cors: {origin: [loopbackOrigin, werkbankOrigin]},
        allowedHosts: [
            "werkbank.wb.local",
            "julius-babies.werkbank.wb.local",
            "werkbank.wbdev.local",
            "julius-babies.werkbank.wbdev.local",
            "julius-babies.wbcloud-dev-juliusbabies-midnight.dev.wbspace.app",
            "wbcloud-dev-juliusbabies-midnight.dev.wbspace.app"
        ]
    }
});
