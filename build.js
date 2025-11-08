import tailwindPlugin from 'bun-plugin-tailwind';
import { parseArgs } from 'util';
import { watch } from 'fs/promises';

const { values, _positionals } = parseArgs({
    args: Bun.argv,
    options: {
        watch: { type: 'boolean' },
    },
    strict: true,
    allowPositionals: true,
});


// >> Build

async function build({entrypoints, outdir, target, plugins}) {
    const build = await Bun.build({
        entrypoints,
        outdir,
        minify: true,
        sourcemap: 'external',
        target,
        plugins,
    });

    if (!build.success) {
        console.error('Build failed');
        for (const message of build.logs) {
            console.error(message);
        }
        process.exit(1);
    }
}

async function buildFrontend() {
    await build({
        entrypoints: ['./build/frontend/app.js'],
        outdir: './target/public',
        target: 'browser',
        plugins: [tailwindPlugin],
    });
}

async function buildBackend() {
    await build({
        entrypoints: ['./build/backend/server.js'],
        outdir: './target',
        target: 'bun',
});
}



// >> Main

console.log('[bun] Building project...');
await buildFrontend();
await buildBackend();

if (values.watch) {
    console.log('[bun/watcher] Watching for changes...');
    const watcher = watch('./build', { recursive: true });

    for await (const {filename} of watcher) {
        console.log(`[bun/watcher] Change detected in ${filename}. Rebuilding...`);
        await buildFrontend();
        await buildBackend();
    }
}
