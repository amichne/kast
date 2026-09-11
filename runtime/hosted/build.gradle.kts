import java.security.MessageDigest

plugins {
    id("kast.kotlin-library")
    id("kast.role.ide-host")
}

base { archivesName.set("kast-ide-hosted") }

val hostedIdeaHome = providers.gradleProperty("hostedIdeaHome")
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val pinnedBuild = catalog.findVersion("ide-host-build").get().requiredVersion
val sdk =
    hostedIdeaHome.orElse(
        gradle.gradleUserHomeDir.resolve("kast/workspace-intellij-read-idea-distributions/$pinnedBuild").path
    )
val platform =
    files(
        sdk.map { home ->
            fileTree(home) {
                include("lib/**/*.jar")
                exclude("lib/intellij.libraries.kotlinx.serialization.*.jar", "lib/intellij.libraries.ktor.utils.jar")
            }
        }
    )

if (!hostedIdeaHome.isPresent) platform.builtBy(":workspace:intellij-read:extractWorkspaceReadIdeaDistribution")

val ideBuild =
    if (hostedIdeaHome.isPresent) {
        val metadata =
            providers
                .fileContents(layout.projectDirectory.file(hostedIdeaHome.get() + "/Resources/product-info.json"))
                .asText
                .get()
        (groovy.json.JsonSlurper().parseText(metadata) as Map<*, *>)["buildNumber"] as String
    } else pinnedBuild
val ideReleaseLine = ideBuild.substringBefore('.')

tasks.processResources {
    val schema = rootProject.file("protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json")
    val registry = rootProject.file("protocol/contract/src/main/resources/ide-hosted/hosted-query.operations.json")
    fun digest(file: File) =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val values =
        mapOf(
            "ideBuild" to ideBuild,
            "ideReleaseLine" to ideReleaseLine,
            "kotlinBuild" to "$ideBuild-IJ",
            "pluginVersion" to project.version.toString(),
            "schemaDigest" to digest(schema),
            "registryDigest" to digest(registry),
        )
    inputs.properties(values)
    expand(values)
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:wire"))
    implementation(project(":query:contract"))
    implementation(project(":query:protocol"))
    implementation(project(":query:service"))
    implementation(project(":symbol:contract"))
    implementation(project(":symbol:service"))
    implementation(project(":symbol:intellij"))
    implementation(project(":source:contract"))
    implementation(project(":source:service"))
    implementation(project(":source:intellij"))
    implementation(project(":relation:contract"))
    implementation(project(":relation:service"))
    implementation(project(":relation:intellij"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":diagnostic:service"))
    implementation(project(":diagnostic:intellij"))
    implementation(project(":traversal:contract"))
    implementation(project(":traversal:service"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(platform)
    testImplementation(platform)
}

val hostedPlugin by
    tasks.registering(Zip::class) {
        group = "distribution"
        description = "Packages the project-owned existing-IDE index endpoint."
        archiveFileName.set("kast-ide-hosted-v${project.version}-idea-$ideReleaseLine.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        into("kast-ide-hosted/lib") {
            from(tasks.jar)
            from(project(":workspace:intellij-read").tasks.named("jar"))
            from(configurations.runtimeClasspath) {
                include(
                    "kernel-*.jar",
                    "workspace-contract-*.jar",
                    "symbol-contract-*.jar",
                    "contract-*.jar",
                    "protocol-wire-*.jar",
                    "protocol-registry-*.jar",
                    "wire-*.jar",
                    "registry-*.jar",
                    "query-contract-*.jar",
                    "query-protocol-*.jar",
                    "query-service-*.jar",
                    "symbol-service-*.jar",
                    "symbol-intellij-*.jar",
                    "source-contract-*.jar",
                    "source-service-*.jar",
                    "source-intellij-*.jar",
                    "relation-contract-*.jar",
                    "relation-service-*.jar",
                    "relation-intellij-*.jar",
                    "traversal-contract-*.jar",
                    "traversal-service-*.jar",
                    "diagnostic-contract-*.jar",
                    "diagnostic-service-*.jar",
                    "diagnostic-intellij-*.jar",
                    "change-contract-*.jar",
                    "kotlinx-serialization-core-jvm-*.jar",
                    "kotlinx-serialization-json-jvm-*.jar",
                )
            }
        }
    }
