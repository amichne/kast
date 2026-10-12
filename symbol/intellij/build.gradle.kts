import org.gradle.api.artifacts.VersionCatalogsExtension
import support.tasks.nativefixtures.NativeFixtureJvmArguments

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.symbol"

base {
    archivesName.set("symbol-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

val symbolIdeaDistribution =
    configurations.create("symbolIdeaDistribution") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

private val extractedIdeaDistributionDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/symbol-intellij-idea-distributions/$ideaPlatformBuild")))
    }

val extractSymbolIdeaDistribution =
    tasks.register<ExtractIdeaDistributionTask>("extractSymbolIdeaDistribution") {
        archives.from(symbolIdeaDistribution)
        ideaVersion.set(ideaPlatformBuild)
        libraryFamilies.set(setOf(IdeaLibraryFamily.PLATFORM, IdeaLibraryFamily.KOTLIN, IdeaLibraryFamily.JAVA))
        outputDirectory.set(extractedIdeaDistributionDirectory)
    }

private val kotlinPluginLibs: ConfigurableFileCollection =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    include("**/plugins/Kotlin/lib/**/*.jar")
                    exclude("**/plugins/Kotlin/lib/jps/**")
                    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
                }
            }
        )
        .builtBy(extractSymbolIdeaDistribution)

private val javaPluginLibs: ConfigurableFileCollection =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    include("**/plugins/java/lib/**/*.jar")
                }
            }
        )
        .builtBy(extractSymbolIdeaDistribution)

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    symbolIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaPlatformBuild@zip") {
        isTransitive = false
    }

    compileOnly("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util-class-loader:$ideaPlatformBuild")
    compileOnly("com.jetbrains.intellij.platform:util-text-matching:$ideaPlatformBuild")
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)

    testImplementation("com.jetbrains.intellij.platform:core:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:boot:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util-class-loader:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:core-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:syntax-psi:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:analysis:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:indexing:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:lang-impl:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:project-model:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:util-text-matching:$ideaPlatformBuild")
    testImplementation(javaPluginLibs)
    testImplementation(kotlinPluginLibs)
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
        from({ zipTree(symbolIdeaDistribution.singleFile) })
        into(layout.buildDirectory.dir("native-test/idea"))
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
        systemProperty("idea.load.plugins.id", "com.intellij.java,org.jetbrains.kotlin,org.toml.lang")
        systemProperty("idea.is.unit.test", "true")
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("kotlin.plugin.mode", "K2")
    }
