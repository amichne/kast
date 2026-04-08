import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.getByType
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

plugins {
    id("kast.standalone-serialization-app")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val intellijIdeaVersion = catalog.findVersion("intellij-idea").get().requiredVersion
private val serializationVersion = catalog.findVersion("serialization").get().requiredVersion

private val prioritizedSerializationRuntime = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion"),
    dependencies.create("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion"),
)

val ideaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/intellij-distributions/$intellijIdeaVersion")))
}

@CacheableTask
abstract class ExtractIdeaDistributionTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    @get:Input
    abstract val ideaVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val archiveFile = archives.singleFile
        val outputRoot = outputDirectory.get().asFile.toPath()
        val versionMarker = outputRoot.resolve(".kast-intellij-version")
        if (Files.isDirectory(outputRoot) && Files.isRegularFile(versionMarker)) {
            if (Files.readString(versionMarker).trim() == ideaVersion.get()) {
                return
            }
        }

        val parent = outputRoot.parent ?: throw GradleException("IntelliJ extraction output must have a parent directory: $outputRoot")
        Files.createDirectories(parent)
        val tempRoot = Files.createTempDirectory(parent, "${outputRoot.fileName}.tmp-")
        try {
            ZipFile(archiveFile).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = tempRoot.resolve(entry.name).normalize()
                    if (!target.startsWith(tempRoot)) {
                        throw GradleException("Zip-slip attempt detected while extracting ${entry.name} from $archiveFile.")
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                        continue
                    }

                    target.parent?.let(Files::createDirectories)
                    archive.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.writeString(tempRoot.resolve(".kast-intellij-version"), ideaVersion.get())
            outputRoot.toFile().deleteRecursively()
            try {
                Files.move(tempRoot, outputRoot, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempRoot, outputRoot, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}

val extractIdeaDistribution: TaskProvider<ExtractIdeaDistributionTask> by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(ideaDistribution)
    ideaVersion.set(intellijIdeaVersion)
    outputDirectory.set(extractedIdeaDistributionDirectory)
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

private val kotlinCompilerJar = extractedIdeaFiles {
    include("**/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar")
}

private val compatCompileLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/testFramework.jar")
    exclude("**/testFramework-k1.jar")
    // Linux CI can pick the IntelliJ-bundled serialization jars ahead of the
    // Gradle-resolved runtime, which breaks the Kotlin serialization plugin's
    // version detection for @Serializable declarations in this module.
    exclude("**/module-intellij.libraries.kotlinx.serialization.core.jar")
    exclude("**/module-intellij.libraries.kotlinx.serialization.json.jar")
}

@CacheableTask
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
        outputRoot.deleteRecursively()
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
    classpath = files(compatCompileLibs, kotlinCompilerJar)
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

val ideaLibs: ConfigurableFileCollection = compatCompileLibs
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

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val writeBackendVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated-resources/kast-backend-version.txt")
    outputs.file(versionFile)
    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(buildVersion.get())
        }
    }
}

sourceSets.main {
    resources.srcDir(writeBackendVersion.map { it.outputs.files.singleFile.parentFile })
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
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.sqlite.jdbc)

    testImplementation(project(":shared-testing"))
    // IJ platform Logger.setFactory() references junit.rules.TestRule at class-init time.
    testRuntimeOnly(libs.junit4)
}

tasks.withType<KotlinCompile>().configureEach {
    if (name != "compileKotlin") {
        return@configureEach
    }

    doFirst {
        val currentLibraries = libraries.files
        // IntelliJ's bundled ktor-utils jar also contains kotlinx.serialization
        // core classes but no version metadata. On Linux it can appear before the
        // real runtime on the compiler classpath, which breaks the serialization
        // plugin's runtime version check for @Serializable declarations.
        libraries.setFrom(prioritizedSerializationRuntime, currentLibraries)
    }
}
