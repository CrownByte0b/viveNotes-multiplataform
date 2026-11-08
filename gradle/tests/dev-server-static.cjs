// The web app's dev server, run for real over a fixture checkout laid out like this one. Kotlin's
// generated config serves the package's kotlin/ folder, the processed resources and the checkout
// root; webApp/webpack.config.d/dev-server.js must leave only the first two.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire } = require("node:module");
const { test } = require("node:test");

const [modules, fragment] = process.argv.slice(2).map(argument => path.resolve(argument));
const load = createRequire(path.join(modules, "dev-server-regression.cjs"));
const webpack = load("webpack");
const Server = load("webpack-dev-server");

async function statuses(withFragment, requests) {
    const checkout = fs.mkdtempSync(path.join(os.tmpdir(), "vive-dev-server-"));
    try {
        const generated = path.join(checkout, "build", "wasm", "packages", "multiplataform-vive-webApp");
        const resources = path.join(checkout, "webApp", "build", "processedResources", "wasmJs", "main");
        fs.mkdirSync(path.join(generated, "kotlin"), { recursive: true });
        fs.mkdirSync(path.join(resources, "ink"), { recursive: true });
        fs.writeFileSync(path.join(checkout, "local.properties"), "vive.googleDesktopClientSecret=fixture\n");
        fs.writeFileSync(path.join(generated, "kotlin", "webApp.mjs"), "export {};\n");
        fs.writeFileSync(path.join(resources, "index.html"), "<!doctype html><title>Vive Notes</title>");
        fs.writeFileSync(path.join(resources, "ink", "byteink-engine.mjs"), "export default () => {};\n");
        fs.writeFileSync(path.join(generated, "entry.js"), "export const app = 1;\n");
        const config = {
            mode: "development",
            context: generated,
            entry: "./entry.js",
            output: { path: path.join(generated, "dist"), filename: "webApp.js" },
            devServer: {
                open: false,
                static: [
                    { directory: path.resolve(generated, "kotlin"), watch: false },
                    { directory: path.resolve(generated, "../../../../webApp/build/processedResources/wasmJs/main"), watch: false },
                    { directory: path.resolve(generated, "../../../.."), watch: false },
                ],
            },
        };
        if (withFragment) {
            vm.runInNewContext(fs.readFileSync(fragment, "utf8"), { config, require: load, __dirname: generated },
                { filename: fragment });
        }
        const server = new Server({ ...config.devServer, host: "127.0.0.1", port: 0, hot: false, liveReload: false,
            client: false }, webpack(config));
        await server.start();
        try {
            const port = server.server.address().port;
            const result = {};
            for (const request of requests) result[request] = (await fetch(`http://127.0.0.1:${port}/${request}`)).status;
            return result;
        } finally {
            await server.stop();
        }
    } finally {
        fs.rmSync(checkout, { recursive: true, force: true });
    }
}

test("without the fragment, Kotlin's static folders publish the checkout", async () => {
    const result = await statuses(false, ["index.html", "local.properties"]);
    assert.deepEqual(result, { "index.html": 200, "local.properties": 200 });
});

test("the app's dev server serves its page, bundle and engine but nothing else of the checkout", async () => {
    const result = await statuses(true, ["index.html", "webApp.mjs", "ink/byteink-engine.mjs", "local.properties",
        "webApp/build/processedResources/wasmJs/main/index.html"]);
    assert.deepEqual(result, {
        "index.html": 200,
        "webApp.mjs": 200,
        "ink/byteink-engine.mjs": 200,
        "local.properties": 404,
        "webApp/build/processedResources/wasmJs/main/index.html": 404,
    });
});
