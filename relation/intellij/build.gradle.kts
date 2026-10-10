import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.process.CommandLineArgumentProvider

abstract class NativeFixtureJvmArguments : CommandLineArgumentProvider {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val productInfo: RegularFileProperty

    @get:Input abstract val pinnedBuild: Property<String>

    @get:Input abstract val osName: Property<String>

    override fun asArguments(): Iterable<String> {
        val info = groovy.json.JsonSlurper().parse(productInfo.get().asFile) as Map<*, *>
        check(info["buildNumber"] == pinnedBuild.get()) { "Native fixture distribution must match the pinned build" }
        val launches = info["launch"] as List<*>
        val platform =
            when {
                osName.get().startsWith("Mac") -> "macOS"
                osName.get().startsWith("Windows") -> "Windows"
                else -> "Linux"
            }
        return launches
            .filter { (it as Map<*, *>)["os"] == platform }
            .flatMap { launch ->
                ((launch as Map<*, *>)["additionalJvmArguments"] as List<*>).filterIsInstance<String>()
            }
            .filter { it.startsWith("--add-opens=") || it.startsWith("--add-exports=") }
            .distinct() + "--enable-native-access=ALL-UNNAMED"
    }
}

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.relation"

base {
    archivesName.set("relation-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

val relationIdeaDistribution =
    configurations.create("relationIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val extractedKotlinPluginDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/relation-intellij-kotlin-plugin/$ideaPlatformBuild")))
    }

val extractRelationKotlinPlugin =
    tasks.register<Sync>("extractRelationKotlinPlugin") {
        inputs.property("pluginLibrarySets", listOf("Kotlin", "java"))
        from({ zipTree(relationIdeaDistribution.singleFile) }) {
            include("**/plugins/Kotlin/lib/**/*.jar")
            include("**/plugins/java/lib/**/*.jar")
            exclude("**/plugins/Kotlin/lib/jps/**")
            exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
            eachFile {
                relativePath =
                    RelativePath(
                        true,
                        *relativePath.segments.dropWhile { segment -> segment != "lib" }.drop(1).toTypedArray(),
                    )
            }
        }
        includeEmptyDirs = false
        into(extractedKotlinPluginDirectory)
    }

private val kotlinPluginLibs: ConfigurableFileCollection =
    files(
            extractedKotlinPluginDirectory.map { directory ->
                fileTree(directory) {
                    include("**/*.jar")
                }
            }
        )
        .builtBy(extractRelationKotlinPlugin)

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":relation:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    relationIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaPlatformBuild@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation(kotlinPluginLibs)
    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
}

// The native call oracle must compile with the repository's compiler before IDE acceptance.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    // Compile the independently authored immutable-callback oracle through the production Kotlin compiler.
    source(rootProject.file("experiments/host-observation/immutable-callback-fixture/invocation/src/main/kotlin"))
    source(rootProject.file("experiments/host-observation/immutable-callback-fixture/forwarding/src/main/kotlin"))
    source(rootProject.file("experiments/host-observation/immutable-callback-fixture/suppliers/src/main/kotlin"))
    source(rootProject.file("experiments/host-observation/semantic-fixture/value-flow/RepresentationImpactFixture.kt"))
    source(rootProject.file("experiments/host-observation/semantic-fixture/value-flow/LocalIdentityOtherFixture.kt"))
    source(rootProject.file("experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinCalls.kt"))
    source(
        rootProject.file("experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinReferenceTarget.kt")
    )
    source(rootProject.file("experiments/host-observation/semantic-fixture/read-reliability/ReadKotlinReferences.kt"))
}

tasks.named("test") {
    inputs.file(rootProject.file("cli/src/test/resources/stabilization/Fixture.kt"))
}

// Indexed Platform fixtures own a separate process and task; parser/pure tests stay fast.
val nativeTest = sourceSets.create("nativeTest")

configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())

nativeTest.compileClasspath += sourceSets.main.get().output

nativeTest.runtimeClasspath += sourceSets.main.get().output

kotlin.target.compilations.getByName("nativeTest").associateWith(kotlin.target.compilations.getByName("main"))

val nativeFixtureIdeaHome = providers.gradleProperty("nativeFixtureIdeaHome")
val nativeIdeaHome =
    objects.directoryProperty().apply {
        if (nativeFixtureIdeaHome.isPresent) set(project.file(nativeFixtureIdeaHome.get()))
        else set(layout.buildDirectory.dir("native-test/idea"))
    }
val extractNativeTestIdea =
    tasks.register<Sync>("extractNativeTestIdea") {
        from({ zipTree(relationIdeaDistribution.singleFile) })
        into(layout.buildDirectory.dir("native-test/idea"))
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
        .apply { if (!nativeFixtureIdeaHome.isPresent) builtBy(extractNativeTestIdea) }

dependencies {
    add(nativeTest.implementationConfigurationName, nativeIdeaLibraries)
    add(nativeTest.implementationConfigurationName, "com.jetbrains.intellij.platform:test-framework:$ideaPlatformBuild")
    add(
        nativeTest.implementationConfigurationName,
        "com.jetbrains.intellij.java:java-test-framework:$ideaPlatformBuild",
    )
    add(
        nativeTest.implementationConfigurationName,
        "com.jetbrains.intellij.kotlin:kotlin-base-test-framework:$ideaPlatformBuild",
    )
    add(
        nativeTest.runtimeOnlyConfigurationName,
        "org.junit.vintage:junit-vintage-engine:${catalog.findVersion("junit").get().requiredVersion}",
    )
}

val nativeFixtureTest =
    tasks.register<Test>("nativeFixtureTest") {
        description = "Runs headless indexed Kotlin fixtures in the pinned IntelliJ Platform."
        group = "verification"
        testClassesDirs = nativeTest.output.classesDirs
        classpath = nativeTest.runtimeClasspath
        dependsOn("detektNativeTest")
        if (!nativeFixtureIdeaHome.isPresent) dependsOn(extractNativeTestIdea)
        maxParallelForks = 1
        jvmArgumentProviders.add(
            objects.newInstance<NativeFixtureJvmArguments>().apply {
                productInfo.set(nativeIdeaHome.file("product-info.json"))
                pinnedBuild.set(ideaPlatformBuild)
                osName.set(providers.systemProperty("os.name"))
            }
        )
        systemProperty("java.awt.headless", "true")
        systemProperty("idea.home.path", nativeIdeaHome.get().asFile.absolutePath)
        systemProperty("idea.config.path", layout.buildDirectory.dir("native-test/config").get().asFile.absolutePath)
        systemProperty("idea.system.path", layout.buildDirectory.dir("native-test/system").get().asFile.absolutePath)
        systemProperty("idea.log.path", layout.buildDirectory.dir("native-test/log").get().asFile.absolutePath)
        systemProperty("idea.plugins.path", nativeIdeaHome.get().dir("plugins").asFile.absolutePath)
        systemProperty("idea.is.unit.test", "true")
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("kotlin.plugin.mode", "K2")
    }
