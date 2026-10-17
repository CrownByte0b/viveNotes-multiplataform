const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire } = require("node:module");
const { test } = require("node:test");

const [modules, fragment] = process.argv.slice(2);
const webpack = require(path.join(modules, "webpack"));

async function bundle(withBridge, verify) {
    const fixture = fs.mkdtempSync(path.join(os.tmpdir(), "vive-ratex-webpack-"));
    try {
        fs.writeFileSync(path.join(fixture, "entry.cjs"),
            'module.exports = [typeof require("ratex-wasm").initRatex, require("other-alias")];');
        fs.writeFileSync(path.join(fixture, "other.cjs"), "module.exports = 42;");
        const config = {
            mode: "development",
            target: "node",
            context: fixture,
            entry: "./entry.cjs",
            output: { path: path.join(fixture, "dist"), filename: "bundle.cjs", library: { type: "commonjs2" } },
            resolve: { modules: [modules], alias: { "other-alias": path.join(fixture, "other.cjs") } }
        };
        if (withBridge) {
            vm.runInNewContext(fs.readFileSync(fragment, "utf8"), {
                config,
                require: createRequire(path.join(modules, "..", "webpack-regression.config.cjs"))
            }, { filename: fragment });
        }
        const compiler = webpack(config);
        const stats = await new Promise((resolve, reject) => {
            compiler.run((error, result) => compiler.close(closeError => {
                if (error || closeError) reject(error || closeError);
                else resolve(result);
            }));
        });
        verify(stats, path.join(config.output.path, config.output.filename));
    } finally {
        fs.rmSync(fixture, { recursive: true, force: true });
    }
}

test("the published RaTeX package rejects Kotlin-style require without the bridge", async () => {
    await bundle(false, stats => {
        assert.equal(stats.hasErrors(), true);
        assert.match(stats.toString({ all: false, errors: true }), /not exported/);
    });
});

test("the app bridge bundles real RaTeX exports and preserves other aliases", async () => {
    await bundle(true, (stats, output) => {
        assert.equal(stats.hasErrors(), false, stats.toString({ all: false, errors: true }));
        assert.deepEqual(require(output), ["function", 42]);
    });
});
