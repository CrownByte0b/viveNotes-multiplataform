// Share Kotlin's managed Chromium with the Wasm Karma runner, allowing an explicit override.
if (!process.env.CHROME_BIN) {
    process.env.CHROME_BIN = require(process.env.VIVE_PLAYWRIGHT_MODULE).chromium.executablePath();
}
