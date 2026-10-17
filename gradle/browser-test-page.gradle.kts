// Kotlin 2.4's test page loads unversioned Mocha from a CDN. Serve the pinned npm assets locally.
tasks.named("prepareWebpackBundleForKotlinJsTests") {
    val mocha = rootProject.layout.buildDirectory.dir("js/node_modules/mocha")
    val bundle = layout.buildDirectory.dir("kotlinJsTest/dist")
    inputs.files(mocha.map { it.file("mocha.js") }, mocha.map { it.file("mocha.css") })
    doLast {
        val directory = bundle.get().asFile
        for (name in listOf("mocha.js", "mocha.css")) {
            mocha.get().file(name).asFile.copyTo(directory.resolve(name), overwrite = true)
        }
        val html = directory.resolve("test.html")
        html.writeText(html.readText()
            .replace("https://unpkg.com/mocha/mocha.js", "mocha.js")
            .replace("https://unpkg.com/mocha/mocha.css", "mocha.css"))
    }
}
