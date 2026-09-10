import java.security.MessageDigest

plugins {
    id("kast.kotlin-library")
    id("kast.role.ide-host")
}

base { archivesName.set("kast-ide-hosted") }

val hostedIdeaHome = providers.gradleProperty("hostedIdeaHome")
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val pinnedBuild = catalog.findVersion("ide-host-build").get().requiredVersion
val sdk = hostedIdeaHome.orElse(gradle.gradleUserHomeDir.resolve("kast/workspace-intellij-read-idea-distributions/$pinnedBuild").path)
val platform = files(sdk.map { home -> fileTree(home) {
    include("lib/**/*.jar")
    exclude("lib/intellij.libraries.kotlinx.serialization.*.jar", "lib/intellij.libraries.ktor.utils.jar")
} })
if (!hostedIdeaHome.isPresent) platform.builtBy(":workspace:intellij-read:extractWorkspaceReadIdeaDistribution")

val ideBuild = if (hostedIdeaHome.isPresent) {
    val metadata = providers.fileContents(layout.projectDirectory.file(hostedIdeaHome.get() + "/Resources/product-info.json")).asText.get()
    (groovy.json.JsonSlurper().parseText(metadata) as Map<*, *>)["buildNumber"] as String
} else pinnedBuild
tasks.processResources {
    val schema = rootProject.file("experiments/host-observation/hosted-query.schema.json")
    val registry = rootProject.file("experiments/host-observation/hosted-query.operations.json")
    fun digest(file: File) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val values = mapOf("ideBuild" to ideBuild, "kotlinBuild" to "$ideBuild-IJ", "schemaDigest" to digest(schema), "registryDigest" to digest(registry))
    inputs.properties(values)
    from(rootProject.file("experiments/host-observation/hosted-plugin/kast-hosted-query.properties"))
    expand(values)
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(platform)
    testImplementation(platform)
}

val hostedPlugin by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the project-owned existing-IDE index endpoint."
    archiveFileName.set("kast-ide-hosted-0.1.0.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("kast-ide-hosted/lib") {
        from(tasks.jar)
        from(project(":workspace:intellij-read").tasks.named("jar"))
        from(configurations.runtimeClasspath) {
            include("kernel-*.jar", "workspace-contract-*.jar", "symbol-contract-*.jar", "contract-*.jar")
        }
    }
}
