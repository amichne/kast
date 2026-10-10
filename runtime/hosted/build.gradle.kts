import java.security.MessageDigest
import support.tasks.nativefixtures.NativeFixtureJvmArguments

plugins {
    id("kast.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
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
                include("lib/**/*.jar", "plugins/gradle*/lib/**/*.jar")
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
    val contract =
        project(":protocol:wire").layout.buildDirectory.file("generated/hosted-contract/hosted-contract.json")
    dependsOn(":protocol:wire:generateHostedContract")
    from(contract)
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
    implementation(project(":change:contract"))
    implementation(project(":change:plan"))
    implementation(project(":change:apply"))
    implementation(project(":change:recovery"))
    implementation(project(":change:verify"))
    implementation(project(":change:protocol"))
    implementation(project(":change:intellij"))
    implementation(project(":evidence:contract"))
    implementation(project(":evidence:sqlite"))
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
    implementation(project(":topology:contract"))
    implementation(project(":topology:build"))
    implementation(project(":topology:intellij"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":diagnostic:service"))
    implementation(project(":diagnostic:intellij"))
    implementation(project(":traversal:contract"))
    implementation(project(":traversal:service"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))
    compileOnly(platform)
    testImplementation(testFixtures(project(":query:protocol")))
    testImplementation(testFixtures(project(":workspace:contract")))
    testImplementation(platform)
    testImplementation(libs.json.schema.validator)
}

val hostedPlugin =
    tasks.register<Zip>("hostedPlugin") {
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
                    "change-contract-*.jar",
                    "change-plan-*.jar",
                    "change-protocol-*.jar",
                    "change-apply-*.jar",
                    "change-recovery-*.jar",
                    "change-verify-*.jar",
                    "change-intellij-*.jar",
                    "evidence-contract-*.jar",
                    "evidence-sqlite-*.jar",
                    "sqlite-jdbc-*.jar",
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
                    "topology-contract-*.jar",
                    "topology-build-*.jar",
                    "topology-intellij-*.jar",
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

// The actual encoder is compared with the exact bytes also validated by CLI schema tests.
tasks.named("test") {
    inputs
        .file(rootProject.layout.projectDirectory.file("cli/src/test/resources/hosted-endpoint-failure-encodings.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Platform status and wait cleanup have a separate effect-boundary lane.
val nativeTest = sourceSets.create("nativeTest")

configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())

configurations[nativeTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

nativeTest.compileClasspath += sourceSets.main.get().output

nativeTest.runtimeClasspath += sourceSets.main.get().output

kotlin.target.compilations.getByName("nativeTest").associateWith(kotlin.target.compilations.getByName("main"))

val nativePlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion
val nativeFixtureIdeaHome = providers.gradleProperty("nativeFixtureIdeaHome")
val nativeIdeaHome =
    objects.directoryProperty().apply {
        if (nativeFixtureIdeaHome.isPresent) set(project.file(nativeFixtureIdeaHome.get()))
        else set(project(":relation:intellij").layout.buildDirectory.dir("native-test/idea"))
    }
val nativeIdeaLibraries =
    files(
            nativeIdeaHome.map { home ->
                fileTree(home) {
                    include(
                        "lib/**/*.jar",
                        "plugins/java/lib/**/*.jar",
                        "plugins/Kotlin/lib/**/*.jar",
                        "plugins/toml/lib/**/*.jar",
                    )
                    exclude("plugins/Kotlin/lib/jps/**", "plugins/Kotlin/lib/kotlinc/**")
                }
            }
        )
        .apply { if (!nativeFixtureIdeaHome.isPresent) builtBy(":relation:intellij:extractNativeTestIdea") }

dependencies {
    add(nativeTest.implementationConfigurationName, nativeIdeaLibraries)
    add(nativeTest.implementationConfigurationName, catalog.findLibrary("coroutines-test").get())
    add(nativeTest.implementationConfigurationName, "junit:junit:4.13.2")
    add(
        nativeTest.implementationConfigurationName,
        "com.jetbrains.intellij.platform:test-framework:$nativePlatformBuild",
    )
    add(
        nativeTest.runtimeOnlyConfigurationName,
        "org.junit.vintage:junit-vintage-engine:${catalog.findVersion("junit").get().requiredVersion}",
    )
    // The enabled Java plugin's auto-test service extends this Platform runtime class.
    add(nativeTest.runtimeOnlyConfigurationName, "com.jetbrains.intellij.platform:test-runner:$nativePlatformBuild")
}

tasks.register<Test>("nativeFixtureTest") {
    description = "Runs native hosted wait and cancellation fixtures in the pinned IntelliJ Platform."
    group = "verification"
    testClassesDirs = nativeTest.output.classesDirs
    classpath = nativeTest.runtimeClasspath
    dependsOn("detektNativeTest")
    maxParallelForks = 1
    jvmArgumentProviders.add(
        objects.newInstance<NativeFixtureJvmArguments>().apply {
            productInfo.set(nativeIdeaHome.file("product-info.json"))
            pinnedBuild.set(nativePlatformBuild)
            osName.set(providers.systemProperty("os.name"))
        }
    )
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.home.path", nativeIdeaHome.get().asFile.absolutePath)
    systemProperty("idea.config.path", layout.buildDirectory.dir("native-test/config").get().asFile.absolutePath)
    systemProperty("idea.system.path", layout.buildDirectory.dir("native-test/system").get().asFile.absolutePath)
    systemProperty("idea.log.path", layout.buildDirectory.dir("native-test/log").get().asFile.absolutePath)
    systemProperty("idea.plugins.path", nativeIdeaHome.get().dir("plugins").asFile.absolutePath)
    systemProperty("idea.load.plugins.id", "com.intellij.java,org.jetbrains.kotlin,org.toml.lang")
    systemProperty("idea.is.unit.test", "true")
    systemProperty("idea.force.use.core.classloader", "true")
    systemProperty("kotlin.plugin.mode", "K2")
}
