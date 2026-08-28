import java.security.MessageDigest
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

val hostedControlArchive = tasks.register<Tar>("assembleIdeHostedControlDist") {
    group = "distribution"
    description = "Builds the hosted default control archive without isolated-runtime metadata."
    dependsOn("stageKastControlProduct")
    from(layout.buildDirectory.dir("control-product")) {
        exclude("share/kast/semantic-runtime.json")
    }
    destinationDirectory.set(layout.buildDirectory.dir("ide-hosted-release-assets"))
    archiveFileName.set("kast-control-v${project.version}-macos-aarch64.tar.gz")
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    eachFile {
        if (relativePath.pathString == "bin/kast") permissions { unix("755") }
    }
}
val releaseDirectory = layout.buildDirectory.dir("release/v${project.version}")
val pluginArchive = project(":ide-plugin").layout.buildDirectory.file(
    "distributions/kast-ide-plugin-${project.version}.zip",
)

val assembleIdeHostedRelease by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the matched control-plus-plugin default release."
    dependsOn(hostedControlArchive, ":ide-plugin:buildPlugin")
    into(releaseDirectory)
    from(hostedControlArchive.flatMap(Tar::getArchiveFile))
    from(pluginArchive)
    doLast {
        destinationDir.listFiles { file -> file.isFile && !file.name.endsWith(".sha256") }
            .orEmpty().sortedBy { it.name }.forEach { asset ->
                val digest = MessageDigest.getInstance("SHA-256")
                asset.inputStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                asset.resolveSibling(asset.name + ".sha256")
                    .writeText("$hex  ${asset.name}\n")
            }
    }
}
