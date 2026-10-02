import java.security.MessageDigest
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

val controlVersion = rootProject.extra["controlVersion"].toString()
val hostVersion = rootProject.extra["hostedPluginVersion"].toString()
val releaseDirectory = layout.buildDirectory.dir("release/v$controlVersion")
val controlArchive = tasks.named<Tar>("assembleKastControlDist")
val pluginArchive = project(":runtime:hosted").tasks.named<Zip>("hostedPlugin")
val agentToolsDirectory = layout.buildDirectory.dir("generated/agent-tools")

val skillArchive = tasks.register<Zip>("assembleKastSkill") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory.map { it.dir("plugins/kast/skills/kast") }) { into("kast") }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-skill-v$controlVersion.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val agentPluginArchive = tasks.register<Zip>("assembleKastAgentPlugin") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory.map { it.dir("plugins/kast") })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-plugin-v$controlVersion.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val marketplaceArchive = tasks.register<Zip>("assembleKastMarketplace") {
    group = "distribution"
    dependsOn("generateKastAgentTools")
    from(agentToolsDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("kast-marketplace-v$controlVersion.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val controlRecord = tasks.named("generateControlReleaseRecord")
val hostRecord = tasks.named("generateHostReleaseRecord")

tasks.register<Sync>("assembleControlRelease") {
    group = "distribution"
    description = "Assembles a control release without producing a hosted plugin."
    dependsOn(controlRecord, "verifyKastControlDistLayout", ":app-server:verifyReleaseRuntimeAdmission")
    into(layout.buildDirectory.dir("release/control-v$controlVersion"))
    from(controlArchive.flatMap(Tar::getArchiveFile), skillArchive.flatMap(Zip::getArchiveFile),
        agentPluginArchive.flatMap(Zip::getArchiveFile), marketplaceArchive.flatMap(Zip::getArchiveFile))
    from(layout.buildDirectory.dir("generated/control-release"))
}

tasks.register<Sync>("assembleHostRelease") {
    group = "distribution"
    description = "Assembles a hosted plugin release without producing control."
    dependsOn(hostRecord)
    into(layout.buildDirectory.dir("release/host-v$hostVersion"))
    from(pluginArchive.flatMap(Zip::getArchiveFile))
    from(layout.projectDirectory.file("packaging/host-installation.py"))
    from(layout.buildDirectory.dir("generated/host-release"))
}

val assembleRelease = tasks.register<Sync>("assembleRelease") {
    group = "distribution"
    description =
        "Assembles a tested fresh-install pair; component versions may differ."
    dependsOn(controlArchive, pluginArchive, skillArchive, agentPluginArchive, marketplaceArchive, hostRecord, "verifyDistributionContent")
    into(releaseDirectory)
    from(controlArchive.flatMap(Tar::getArchiveFile))
    from(pluginArchive.flatMap(Zip::getArchiveFile))
    from(skillArchive.flatMap(Zip::getArchiveFile))
    from(agentPluginArchive.flatMap(Zip::getArchiveFile))
    from(marketplaceArchive.flatMap(Zip::getArchiveFile))
    from(layout.buildDirectory.dir("generated/host-release"))
    from(layout.projectDirectory.file("packaging/host-installation.py"))
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
