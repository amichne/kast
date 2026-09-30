import java.security.MessageDigest
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

val releaseDirectory = layout.buildDirectory.dir("release/v${project.version}")
val controlArchive = tasks.named<Tar>("assembleKastControlDist")
val pluginArchive = project(":runtime:hosted").tasks.named<Zip>("hostedPlugin")
val agentToolsDirectory = layout.buildDirectory.dir("generated/agent-tools")

val skillArchive = tasks.register<Zip>("assembleKastSkill") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory.map { it.dir("plugins/kast/skills/kast") }) { into("kast") }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-skill-v${project.version}.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val agentPluginArchive = tasks.register<Zip>("assembleKastAgentPlugin") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory.map { it.dir("plugins/kast") })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-plugin-v${project.version}.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val marketplaceArchive = tasks.register<Zip>("assembleKastMarketplace") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-marketplace-v${project.version}.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val assembleRelease = tasks.register<Sync>("assembleRelease") {
    group = "distribution"
    description =
        "Publishes the matched control, IDEA plugin, agent skill, plugin, and marketplace."
    dependsOn(controlArchive, pluginArchive, skillArchive, agentPluginArchive, marketplaceArchive, "verifyDistributionContent")
    into(releaseDirectory)
    from(controlArchive.flatMap(Tar::getArchiveFile))
    from(pluginArchive.flatMap(Zip::getArchiveFile))
    from(skillArchive.flatMap(Zip::getArchiveFile))
    from(agentPluginArchive.flatMap(Zip::getArchiveFile))
    from(marketplaceArchive.flatMap(Zip::getArchiveFile))
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
