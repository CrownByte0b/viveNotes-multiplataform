// The dev server publishes only the app: its bundle and processed resources. Kotlin also adds the
// repository root (for browser source maps), and the server listens on every network interface, so
// that would hand the whole checkout, local.properties included, to the local network. Drop every
// static folder that contains this generated package, which is to say the checkout around it.
if (config.devServer && Array.isArray(config.devServer.static)) {
    const vivePath = require("path");
    const vivePackage = vivePath.resolve(__dirname);
    config.devServer.static = config.devServer.static.filter(entry => {
        const directory = vivePath.resolve(typeof entry === "string" ? entry : entry.directory);
        return directory !== vivePackage && !vivePackage.startsWith(directory + vivePath.sep);
    });
}
