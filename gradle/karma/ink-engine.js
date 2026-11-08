// Serve ByteInk's Ink engine to the Wasm browser tests that draw, at /ink/ as the web app serves it.
// Only test tasks that set VIVE_INK_ENGINE_DIR (shared's wasmJs tests) get it.
if (process.env.VIVE_INK_ENGINE_DIR) {
    const engine = require('path').resolve(process.env.VIVE_INK_ENGINE_DIR);
    config.files.push({ pattern: engine + '/**/*', included: false, served: true, watched: false, nocache: true });
    config.proxies = Object.assign({}, config.proxies, { '/ink/': '/absolute' + engine + '/' });
    config.mime = Object.assign({}, config.mime, { 'text/javascript': ['mjs'], 'application/wasm': ['wasm'] });
}
