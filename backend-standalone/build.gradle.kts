import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.getByType
import java.util.zip.ZipFile

plugins {
    id("kast.standalone-app")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellijIdeaVersion = catalog.findVersion("intellij-idea").get().requiredVersion

val ideaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = layout.buildDirectory.dir("intellij-distribution")

val extractIdeaDistribution: TaskProvider<Sync> by tasks.registering(Sync::class) {
    from({
        ideaDistribution.files.map(::zipTree)
    })
    into(extractedIdeaDistributionDirectory)
    includeEmptyDirs = false
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) {
            configure()
        }
    },
).builtBy(extractIdeaDistribution)

private val appJar = extractedIdeaFiles {
    include("**/lib/app.jar")
    exclude("**/plugins/**")
}

private val kotlinCompilerJar = extractedIdeaFiles {
    include("**/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar")
}

abstract class ExtractLegacyPluginClassesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ideaDistributionDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val distributionRoot = ideaDistributionDirectory.get().asFile
        val compilerJar = distributionRoot.walkTopDown()
            .firstOrNull { file ->
                file.isFile && file.name == "kotlin-compiler.jar" &&
                    file.invariantSeparatorsPath.contains("/plugins/Kotlin/kotlinc/lib/")
            }
            ?: throw GradleException(
                "IntelliJ IDEA distribution under $distributionRoot did not contain plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar.",
            )

        val excludedEntries = setOf(
            "com/intellij/ide/plugins/ContainerDescriptor.class",
            "com/intellij/ide/plugins/IdeaPluginDescriptorImpl.class",
            "com/intellij/ide/plugins/IdeaPluginDescriptorImplKt.class",
            "com/intellij/ide/plugins/PluginDescriptorLoader.class",
            $$"com/intellij/ide/plugins/PluginDescriptorLoader$loadForCoreEnv$1.class",
            "com/intellij/ide/plugins/DataLoader.class",
            "com/intellij/ide/plugins/ImmutableZipFileDataLoader.class",
            "com/intellij/ide/plugins/NonShareableJavaZipFilePool.class",
        )
        val excludedPrefixes = listOf(
            "com/intellij/ide/plugins/ImmutableZipFileDataLoader$",
            "com/intellij/ide/plugins/NonShareableJavaZipFilePool$",
        )
        val outputRoot = outputDirectory.get().asFile
        project.delete(outputRoot)
        outputRoot.mkdirs()

        ZipFile(compilerJar).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }

                val name = entry.name
                val included =
                    name.startsWith("com/intellij/ide/plugins/") && name.endsWith(".class") ||
                        name == "com/intellij/util/messages/ListenerDescriptor.class"
                val excluded = name in excludedEntries || excludedPrefixes.any(name::startsWith)
                if (!included || excluded) {
                    continue
                }

                val target = outputRoot.resolve(name)
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

val extractLegacyPluginClasses: TaskProvider<ExtractLegacyPluginClassesTask> by tasks.registering(ExtractLegacyPluginClassesTask::class) {
    dependsOn(extractIdeaDistribution)
    ideaDistributionDirectory.set(extractedIdeaDistributionDirectory)
    outputDirectory.set(layout.buildDirectory.dir("legacy-plugin-classes"))
}

// ───────────────────────────────────────────────────────────────────────────────
// Compat JAR strategy
//
// The AA (analysis-api-standalone-for-ide) was compiled against an early IJ 2025.3
// EAP. The stable IJ 2025.3 distribution has two sets of conflicting classes in
// the com.intellij.ide.plugins package:
//
//  ┌─────────────────────────┬─────────────────────────────────────────────────────────┐
//  │ Source                  │ Required for                                            │
//  ├─────────────────────────┼─────────────────────────────────────────────────────────┤
//  │ app.jar (IJ 2025.3)     │ PluginDescriptorLoader, IdeaPluginDescriptorImpl        │
//  │                         │ (abstract base for PluginMainDescriptor /               │
//  │                         │ PluginModuleDescriptor created by KotlinCoreEnvironment)│
//  ├─────────────────────────┼─────────────────────────────────────────────────────────┤
//  │ kotlin-compiler.jar     │ RawPluginDescriptor, ReadModuleContext, XmlReader,      │
//  │ (OLD, via compat JAR)   │ PluginXmlPathResolver, PathResolver (4-arg resolvePath) │
//  │                         │ — all used by PluginStructureProvider in the AA.        │
//  └─────────────────────────┴─────────────────────────────────────────────────────────┘
//
// ContainerDescriptor is special: IJ 2025.3's ParserElementsConversionKt calls the
// 4-arg constructor, while the AA calls getServices(). Neither old nor new provides
// both; we compile a hybrid that does.
//
// The compat JAR is placed FIRST on the classpath to win the class-loading race for
// the classes listed above that must come from kotlin-compiler.jar.
// ───────────────────────────────────────────────────────────────────────────────

val compileCompatJava: TaskProvider<JavaCompile> by tasks.registering(JavaCompile::class) {
    dependsOn(extractIdeaDistribution)
    source = fileTree("src/compat/java") { include("**/*.java") }
    classpath = files(appJar, kotlinCompilerJar)
    destinationDirectory.set(layout.buildDirectory.dir("compat-classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("-Xlint:-rawtypes", "-Xlint:-unchecked"))
}

val buildIdeCompatJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(compileCompatJava, extractLegacyPluginClasses)
    archiveFileName.set("ide-plugin-compat.jar")
    destinationDirectory.set(layout.buildDirectory.dir("compat"))
    // 1. Hybrid ContainerDescriptor must come first to win the duplicate race.
    from(compileCompatJava)
    from(extractLegacyPluginClasses)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    // testFramework.jar auto-registers ThreadLeakTrackerExtension (JUnit 5 extension)
    // which needs --add-opens for javax.swing and is not needed for standalone analysis.
    exclude("**/testFramework.jar")
    exclude("**/testFramework-k1.jar")
}
val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    // kotlin-jps-plugin.jar ships an old Java CompilerConfiguration (no Kotlin companion)
    // that shadows the correct version in kotlin-compiler-common.jar → NoSuchFieldError.
    exclude("**/plugins/Kotlin/lib/jps/**")
}
val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

application {
    mainClass = "io.github.amichne.kast.standalone.StandaloneMainKt"
}

dependencies {
    ideaDistribution("com.jetbrains.intellij.idea:ideaIC:$intellijIdeaVersion@zip") {
        isTransitive = false
    }

    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(libs.analysis.api.standalone) {
        isTransitive = false
    }
    implementation(libs.gradle.tooling.api)
    // compat JAR FIRST — wins the class-loading race for old-API classes
    implementation(files(buildIdeCompatJar.map { it.archiveFile }))
    implementation(ideaLibs)
    implementation(kotlinPluginLibs)
    implementation(javaPluginLibs)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)
    testImplementation(project(":shared-testing"))
    // IJ platform Logger.setFactory() references junit.rules.TestRule at class-init time.
    testRuntimeOnly(libs.junit4)
}
