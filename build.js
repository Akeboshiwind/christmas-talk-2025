import tailwindPlugin from 'bun-plugin-tailwind';
import { parseArgs } from 'util';
import { watch, copyFile, mkdir } from 'fs/promises';

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
        minify: !values.watch,
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
    await mkdir('./target/public', { recursive: true });
    await build({
        entrypoints: ['./build/talk/app.js'],
        outdir: './target/public',
        target: 'browser',
        plugins: [tailwindPlugin],
    });
    await copyFile('./src/index.html', './target/public/index.html');
    await copyFile('./src/IMG_5313.smaller.jpeg', './target/public/persimmon.jpeg');
}



// >> Main

console.log('[bun] Building project...');
await buildFrontend();

if (values.watch) {
    console.log('[bun/watcher] Watching for changes...');
    const watcher = watch('./build', { recursive: true });

    for await (const {filename} of watcher) {
        console.log(`[bun/watcher] Change detected in ${filename}. Rebuilding...`);
        await buildFrontend();
    }
}
