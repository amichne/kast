import org.gradle.api.artifacts.VersionCatalogsExtension
import support.tasks.idea.IntellijSdkLibraryFamily
import support.tasks.idea.selectedIdeaProductInfo
import support.tasks.idea.selectedIntellijTarget
import support.tasks.nativefixtures.NativeFixtureJvmArguments
import support.tasks.nativefixtures.SharedNativeFixtureIdea

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${providers.gradleProperty("GROUP").get()}.symbol"

base {
    archivesName.set("symbol-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellij = selectedIntellijTarget(project).also { it.configureBuildDirectory(project) }
private val ideaPlatformBuild = intellij.profile.ideBuild
private val sdkLibraries =
    intellij.libraries(
        project,
        IntellijSdkLibraryFamily.PLATFORM,
        IntellijSdkLibraryFamily.KOTLIN,
        IntellijSdkLibraryFamily.JAVA,
    )

// Preserve the public producer name while one workspace owner extracts each SDK.
val extractSymbolIdeaDistribution =
    tasks.register("extractSymbolIdeaDistribution") {
        dependsOn(sdkLibraries)
    }

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    compileOnly(sdkLibraries)
    testImplementation(sdkLibraries)
}

// Indexed Platform fixtures own a separate process and task; parser/pure tests stay fast.
val nativeTest = sourceSets.create("nativeTest")

configurations[nativeTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())

nativeTest.compileClasspath += sourceSets.main.get().output

nativeTest.runtimeClasspath += sourceSets.main.get().output

kotlin.target.compilations.getByName("nativeTest").associateWith(kotlin.target.compilations.getByName("main"))

val nativeFixtureIdea = SharedNativeFixtureIdea.select(project, intellij)
val nativeIdeaHome = nativeFixtureIdea.home
val extractNativeTestIdea =
    tasks.register("extractNativeTestIdea") {
        dependsOn(nativeFixtureIdea.contents)
    }
val nativeIdeaLibraries =
    files(
            nativeIdeaHome.map { home ->
                fileTree(home) {
                    include(
                        "lib/**/*.jar",
                        "plugins/java/lib/**/*.jar",
                        "plugins/platform-structuralSearch-plugin/lib/**/*.jar",
                        "plugins/Kotlin/lib/**/*.jar",
                        "plugins/toml/lib/**/*.jar",
                    )
                    exclude("plugins/Kotlin/lib/jps/**", "plugins/Kotlin/lib/kotlinc/**")
                }
            }
        )
        .apply { if (intellij.suppliedHome == null) builtBy(extractNativeTestIdea) }

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

fun registerSymbolNativeFixtures(taskName: String, selectedClasses: List<String>) =
    tasks.register<Test>(taskName) {
        description = "Runs headless indexed Kotlin fixtures in the pinned IntelliJ Platform."
        group = "verification"
        testClassesDirs = nativeTest.output.classesDirs
        classpath = nativeTest.runtimeClasspath
        dependsOn("detektNativeTest")
        if (intellij.suppliedHome == null) dependsOn(extractNativeTestIdea)
        if (selectedClasses.isNotEmpty()) {
            dependsOn(":verifyKotlinSemanticInputs")
            filter { selectedClasses.forEach(::includeTestsMatching) }
        }
        maxParallelForks = 1
        jvmArgumentProviders.add(
            objects.newInstance<NativeFixtureJvmArguments>().apply {
                productInfo.set(layout.file(nativeIdeaHome.map { selectedIdeaProductInfo(it.asFile) }))
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
        systemProperty("idea.load.plugins.id", "com.intellij.java,org.jetbrains.kotlin,org.toml.lang")
        systemProperty("idea.is.unit.test", "true")
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("kotlin.plugin.mode", "K2")
    }

registerSymbolNativeFixtures("nativeFixtureTest", emptyList())

registerSymbolNativeFixtures("semanticCoverageFixtureTest", listOf("*LocalDeclarationOwnerNativeTest"))
