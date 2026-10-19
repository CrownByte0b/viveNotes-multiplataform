// Kotlin/JS emits require(), while RaTeX 0.1.14 publishes only an ESM import entry.
const viveRatexDirectory = require("path").dirname(require.resolve("ratex-wasm/fonts.css"));
config.resolve = config.resolve || {};
config.resolve.alias = {
    ...config.resolve.alias,
    "ratex-wasm$": require("path").join(viveRatexDirectory, "dist/index.js")
};
