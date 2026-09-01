import java.security.MessageDigest
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

val releaseDirectory = layout.buildDirectory.dir("release/v${project.version}")
val controlArchive = tasks.named<Tar>("assembleKastControlDist")
val sidecarArchive = tasks.named<Zip>("semanticRuntimeArchive")

val assembleSidecarRelease by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles matched control and private sidecar assets without a public plugin."
    dependsOn(controlArchive, sidecarArchive)
    into(releaseDirectory)
    from(controlArchive.flatMap(Tar::getArchiveFile))
    from(sidecarArchive.flatMap(Zip::getArchiveFile))
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
