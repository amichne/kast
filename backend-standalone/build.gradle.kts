import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ShrinkRuntimeLibsTask
import WriteWrapperScriptTask
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

        val parent = outputRoot.parent
                     ?: throw GradleException("IntelliJ extraction output must have a parent directory: $outputRoot")
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

// Absent from a standalone class-loading audit that exercised diagnostics, resolve,
// references, and callers against this Gradle workspace.
private val auditedHeadlessIdeaLibExcludes = listOf(
    "**/lib/groovy.jar",
    "**/lib/app-backend.jar",
    "**/lib/lib.jar",
    "**/lib/module-intellij.libraries.bouncy.castle.pgp.jar",
    "**/lib/module-intellij.libraries.cglib.jar",
    "**/lib/module-intellij.libraries.classgraph.jar",
    "**/lib/module-intellij.libraries.commons.io.jar",
    "**/lib/module-intellij.libraries.commons.lang3.jar",
    "**/lib/module-intellij.libraries.commons.compress.jar",
    "**/lib/module-intellij.libraries.jackson.databind.jar",
    "**/lib/module-intellij.libraries.jackson.dataformat.yaml.jar",
    "**/lib/module-intellij.libraries.jackson.jar",
    "**/lib/module-intellij.libraries.jackson.jr.objects.jar",
    "**/lib/module-intellij.libraries.jackson.module.kotlin.jar",
    "**/lib/module-intellij.libraries.jsonpath.jar",
    "**/lib/module-intellij.libraries.kryo5.jar",
    "**/lib/module-intellij.libraries.lz4.jar",
    "**/lib/module-intellij.libraries.markdown.jar",
    "**/lib/module-intellij.libraries.bouncy.castle.provider.jar",
    "**/lib/module-intellij.libraries.commons.imaging.jar",
    "**/lib/module-intellij.libraries.http.client.jar",
    "**/lib/module-intellij.libraries.icu4j.jar",
    "**/lib/module-intellij.libraries.jcef.jar",
    "**/lib/module-intellij.libraries.jsvg.jar",
    "**/lib/module-intellij.libraries.ktor.client.cio.jar",
    "**/lib/module-intellij.libraries.ktor.client.jar",
    "**/lib/module-intellij.libraries.ktor.io.jar",
    "**/lib/module-intellij.libraries.ktor.network.tls.jar",
    "**/lib/module-intellij.libraries.kotlinx.datetime.jar",
    "**/lib/module-intellij.libraries.kotlinx.html.jar",
    "**/lib/module-intellij.libraries.rhino.jar",
    "**/lib/module-intellij.libraries.snakeyaml.engine.jar",
    "**/lib/module-intellij.libraries.snakeyaml.jar",
    "**/lib/module-intellij.libraries.sshj.jar",
    "**/lib/module-intellij.libraries.velocity.jar",
    "**/lib/module-intellij.libraries.xstream.jar",
    "**/lib/module-intellij.libraries.xerces.jar",
    "**/lib/module-intellij.platform.debugger.impl.rpc.jar",
    "**/lib/module-intellij.platform.debugger.impl.shared.jar",
    "**/lib/module-intellij.platform.eel.impl.jar",
    "**/lib/module-intellij.platform.polySymbols.backend.jar",
    "**/lib/module-intellij.platform.polySymbols.jar",
    "**/lib/module-intellij.platform.vcs.core.jar",
    "**/lib/module-intellij.platform.vcs.jar",
    "**/lib/module-intellij.platform.vcs.shared.jar",
    "**/lib/module-intellij.regexp.jar",
    "**/lib/module-intellij.xml.analysis.impl.jar",
    "**/lib/module-intellij.xml.analysis.jar",
    "**/lib/module-intellij.xml.dom.impl.jar",
    "**/lib/module-intellij.xml.dom.jar",
    "**/lib/module-intellij.xml.impl.jar",
    "**/lib/module-intellij.xml.parser.jar",
    "**/lib/module-intellij.xml.psi.impl.jar",
    "**/lib/module-intellij.xml.psi.jar",
    "**/lib/module-intellij.xml.structureView.impl.jar",
    "**/lib/module-intellij.xml.structureView.jar",
    "**/lib/module-intellij.xml.syntax.jar",
    "**/lib/module-intellij.xml.ui.common.jar",
    "**/lib/jaxb-api.jar",
    "**/lib/jaxb-runtime.jar",
    "**/lib/jps-model.jar",
    "**/lib/protobuf.jar",
    "**/lib/rd.jar",
    "**/lib/rhino.jar",
    "**/lib/stats.jar",
    "**/lib/modules/intellij.debugger.streams.backend.jar",
    "**/lib/modules/intellij.debugger.streams.core.jar",
    "**/lib/modules/intellij.debugger.streams.shared.jar",
    "**/lib/modules/intellij.emojipicker.jar",
    "**/lib/modules/intellij.grid.core.impl.jar",
    "**/lib/modules/intellij.grid.impl.jar",
    "**/lib/modules/intellij.ide.startup.importSettings.jar",
    "**/lib/modules/intellij.libraries.coil.jar",
    "**/lib/modules/intellij.libraries.grpc.jar",
    "**/lib/modules/intellij.libraries.grpc.netty.shaded.jar",
    "**/lib/modules/intellij.libraries.lucene.common.jar",
    "**/lib/modules/intellij.libraries.compose.foundation.desktop.jar",
    "**/lib/modules/intellij.libraries.compose.runtime.desktop.jar",
    "**/lib/modules/intellij.libraries.skiko.jar",
    "**/lib/modules/intellij.platform.collaborationTools.jar",
    "**/lib/modules/intellij.platform.compose.jar",
    "**/lib/modules/intellij.platform.compose.markdown.jar",
    "**/lib/modules/intellij.platform.coverage.agent.jar",
    "**/lib/modules/intellij.platform.coverage.jar",
    "**/lib/modules/intellij.platform.debugger.impl.backend.jar",
    "**/lib/modules/intellij.platform.debugger.impl.frontend.jar",
    "**/lib/modules/intellij.platform.eel.impl.jar",
    "**/lib/modules/intellij.platform.execution.dashboard.jar",
    "**/lib/modules/intellij.platform.ide.newUiOnboarding.jar",
    "**/lib/modules/intellij.platform.ide.newUsersOnboarding.jar",
    "**/lib/modules/intellij.platform.jewel.foundation.jar",
    "**/lib/modules/intellij.platform.jewel.ideLafBridge.jar",
    "**/lib/modules/intellij.platform.jewel.markdown.core.jar",
    "**/lib/modules/intellij.platform.jewel.markdown.ideLafBridgeStyling.jar",
    "**/lib/modules/intellij.platform.jewel.ui.jar",
    "**/lib/modules/intellij.platform.lvcs.impl.jar",
    "**/lib/modules/intellij.platform.langInjection.backend.jar",
    "**/lib/modules/intellij.platform.langInjection.jar",
    "**/lib/modules/intellij.platform.scriptDebugger.backend.jar",
    "**/lib/modules/intellij.platform.scriptDebugger.protocolReaderRuntime.jar",
    "**/lib/modules/intellij.platform.scriptDebugger.ui.jar",
    "**/lib/modules/intellij.platform.smRunner.vcs.jar",
    "**/lib/modules/intellij.platform.searchEverywhere.frontend.jar",
    "**/lib/modules/intellij.platform.searchEverywhere.jar",
    "**/lib/modules/intellij.platform.vcs.dvcs.impl.jar",
    "**/lib/modules/intellij.platform.vcs.dvcs.impl.shared.jar",
    "**/lib/modules/intellij.platform.vcs.dvcs.jar",
    "**/lib/modules/intellij.platform.vcs.impl.exec.jar",
    "**/lib/modules/intellij.platform.vcs.impl.frontend.jar",
    "**/lib/modules/intellij.platform.vcs.impl.jar",
    "**/lib/modules/intellij.platform.vcs.impl.lang.actions.jar",
    "**/lib/modules/intellij.platform.vcs.impl.lang.jar",
    "**/lib/modules/intellij.platform.vcs.impl.shared.jar",
    "**/lib/modules/intellij.platform.vcs.log.graph.impl.jar",
    "**/lib/modules/intellij.platform.vcs.log.graph.jar",
    "**/lib/modules/intellij.platform.vcs.log.impl.jar",
    "**/lib/modules/intellij.platform.vcs.log.jar",
    "**/lib/modules/intellij.relaxng.jar",
    "**/lib/modules/intellij.libraries.xml.rpc.jar",
    "**/lib/modules/intellij.settingsSync.core.jar",
    "**/lib/modules/intellij.spellchecker.xml.jar",
    "**/lib/modules/intellij.xml.langInjection.jar",
    "**/lib/modules/intellij.xml.langInjection.xpath.jar",
    "**/lib/modules/intellij.xml.xmlbeans.jar",
)

private val auditedHeadlessKotlinPluginExcludes = listOf(
    "**/plugins/Kotlin/lib/kotlinc.compose-compiler-plugin.jar",
    "**/plugins/Kotlin/lib/kotlinc.kotlin-dataframe-compiler-plugin.jar",
    "**/plugins/Kotlin/lib/kotlinc.kotlin-jps-common.jar",
    "**/plugins/Kotlin/lib/kotlinc.scripting-compiler-plugin.jar",
    "**/plugins/Kotlin/lib/kotlinc.kotlinx-serialization-compiler-plugin.jar",
    "**/plugins/Kotlin/lib/kotlin-gradle-tooling.jar",
    "**/plugins/Kotlin/lib/kotlin-base-jps.jar",
    "**/plugins/Kotlin/lib/kotlin-plugin-shared.jar",
    "**/plugins/Kotlin/lib/jackson-dataformat-toml.jar",
    "**/plugins/Kotlin/lib/vavr.jar",
)

private val auditedHeadlessJavaPluginExcludes = listOf(
    "**/plugins/java/lib/debugger-memory-agent.jar",
    "**/plugins/java/lib/ecj/eclipse.jar",
    "**/plugins/java/lib/jps-builders-6.jar",
    "**/plugins/java/lib/jps-builders.jar",
    "**/plugins/java/lib/jps-javac-extension.jar",
    "**/plugins/java/lib/jps-launcher.jar",
    "**/plugins/java/lib/jb-jdi.jar",
    "**/plugins/java/lib/modules/intellij.java.langInjection.jar",
    "**/plugins/java/lib/modules/intellij.java.langInjection.jps.jar",
    "**/plugins/java/lib/modules/intellij.java.vcs.jar",
    "**/plugins/java/lib/modules/intellij.jvm.analysis.impl.jar",
    "**/plugins/java/lib/kotlin-metadata.jar",
    "**/plugins/java/lib/rt/debugger-agent.jar",
    "**/plugins/java/lib/rt/netty-jps.jar",
)

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

private val ideaRuntimeLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude(auditedHeadlessIdeaLibExcludes)
    exclude("**/plugins/**")
    exclude("**/testFramework.jar")
    exclude("**/testFramework-k1.jar")
    // Keep the Gradle-resolved serialization runtime ahead of IntelliJ-bundled copies.
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

val extractLegacyPluginClasses: TaskProvider<ExtractLegacyPluginClassesTask> by tasks.registering(
    ExtractLegacyPluginClassesTask::class
) {
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

val ideaLibs: ConfigurableFileCollection = ideaRuntimeLibs
val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude(auditedHeadlessKotlinPluginExcludes)
    // kotlin-jps-plugin.jar ships an old Java CompilerConfiguration (no Kotlin companion)
    // that shadows the correct version in kotlin-compiler-common.jar → NoSuchFieldError.
    exclude("**/plugins/Kotlin/lib/jps/**")
}
val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
    exclude(auditedHeadlessJavaPluginExcludes)
}

application {
    mainClass = "io.github.amichne.kast.standalone.StandaloneMainKt"
}

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val writeBackendVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated-resources/kast-backend-version.txt")
    // Capture as a local so the doLast lambda does not close over the build-script instance,
    // which is null when the configuration cache deserializes the action.
    val versionProvider = buildVersion
    outputs.file(versionFile)
    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(versionProvider.get())
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
    implementation(project(":backend-shared"))
    implementation(project(":index-store"))
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

    testImplementation(project(":shared-testing"))
    // IJ platform Logger.setFactory() references junit.rules.TestRule at class-init time.
    testRuntimeOnly(libs.junit4)
}

tasks.withType<KotlinCompile>().configureEach {
    if (name != "compileKotlin") {
        return@configureEach
    }

    // Resolve to concrete files at configuration time. Configuration objects cannot be
    // serialized for the configuration cache; a Set<File> can.
    val serializationJarFiles: Set<File> = prioritizedSerializationRuntime.resolve()
    doFirst {
        val currentLibraries = libraries.files
        // IntelliJ's bundled ktor-utils jar also contains kotlinx.serialization
        // core classes but no version metadata. On Linux it can appear before the
        // real runtime on the compiler classpath, which breaks the serialization
        // plugin's runtime version check for @Serializable declarations.
        libraries.setFrom(serializationJarFiles, currentLibraries)
    }
}

// Rename the launcher to kast-standalone and switch to classpath-based launch
// (as opposed to the default shadow-jar -jar launcher) to honour IntelliJ
// classpath ordering that the fat-jar approach cannot guarantee.
tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-standalone"))
    val dollar = "\$"
    scriptContent.set(
        """
        #!/usr/bin/env bash
        set -euo pipefail

        script_dir="$(cd -- "$(dirname -- "${dollar}{BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
        main_class="io.github.amichne.kast.standalone.StandaloneMainKt"
        runtime_libs_dir="${dollar}{KAST_STANDALONE_RUNTIME_LIBS:-${dollar}{script_dir}/runtime-libs}"

        if [[ ! -d "${dollar}{runtime_libs_dir}" ]]; then
          echo "kast-standalone: runtime-libs directory not found: ${dollar}{runtime_libs_dir}" >&2
          echo "hint: reinstall with kast.sh or set KAST_STANDALONE_RUNTIME_LIBS=/path/to/runtime-libs" >&2
          exit 1
        fi

        classpath_file="${dollar}{runtime_libs_dir}/classpath.txt"
        if [[ ! -f "${dollar}{classpath_file}" ]]; then
          echo "kast-standalone: classpath.txt not found in ${dollar}{runtime_libs_dir}" >&2
          exit 1
        fi

        classpath=""
        while IFS= read -r jar; do
          [[ -z "${dollar}{jar}" ]] && continue
          if [[ -z "${dollar}{classpath}" ]]; then
            classpath="${dollar}{runtime_libs_dir}/${dollar}{jar}"
          else
            classpath="${dollar}{classpath}:${dollar}{runtime_libs_dir}/${dollar}{jar}"
          fi
        done < "${dollar}{classpath_file}"

        if [[ -z "${dollar}{classpath}" ]]; then
          echo "kast-standalone: classpath.txt is empty in ${dollar}{runtime_libs_dir}" >&2
          exit 1
        fi

        java_exec="${dollar}{JAVA_HOME:+${dollar}{JAVA_HOME}/bin/java}"
        java_exec="${dollar}{java_exec:-java}"

        exec "${dollar}{java_exec}" ${dollar}{JAVA_OPTS:-} -cp "${dollar}{classpath}" "${dollar}{main_class}" "$@"
        """.trimIndent(),
    )
}

tasks.named<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    requiredClassEntries.add("io/github/amichne/kast/api/client/StandaloneServerOptions.class")
    requiredClassEntries.add("com/intellij/openapi/util/Disposer.class")
    requiredClassEntries.add("com/intellij/openapi/application/ApplicationManager.class")
    requiredClassEntries.add("com/intellij/openapi/vfs/VirtualFileManager.class")
    requiredClassEntries.add("com/intellij/psi/PsiManager.class")
    requiredClassEntries.add("com/intellij/psi/PsiClass.class")
    requiredClassEntries.add("com/intellij/lang/LanguageParserDefinitions.class")
    requiredClassEntries.add("com/intellij/lang/java/JavaParserDefinition.class")
    requiredClassEntries.add("com/intellij/platform/syntax/psi/ElementTypeConverters.class")
    requiredClassEntries.add("org/jetbrains/kotlin/psi/KtFile.class")
    requiredClassEntries.add("org/jetbrains/kotlin/idea/references/KtReference.class")
    requiredClassEntries.add("org/jetbrains/kotlin/analysis/api/standalone/StandaloneAnalysisAPISession.class")
}

val shrinkRuntimeEnabled = providers.gradleProperty("kast.shrinkRuntime")
    .map(String::toBoolean)
    .getOrElse(false)

if (shrinkRuntimeEnabled) {
    val shrinkRuntimeLibs by tasks.registering(ShrinkRuntimeLibsTask::class) {
        dependsOn("syncRuntimeLibs")
        // The output path of syncRuntimeLibs is fixed by the convention plugin.
        inputDirectory.set(layout.buildDirectory.dir("runtime-libs"))
        libraryJars.from(ideaLibs, kotlinPluginLibs, javaPluginLibs)
        proguardRules.set(layout.projectDirectory.file("src/main/proguard/standalone-rules.pro"))
        outputDirectory.set(layout.buildDirectory.dir("shrunk-runtime-libs"))
        outputClasspathFile.set(layout.buildDirectory.file("shrunk-runtime-libs/classpath.txt"))
    }

    tasks.named<Sync>("syncPortableDist") {
        from(layout.buildDirectory.dir("shrunk-runtime-libs")) {
            into("runtime-libs")
        }
        dependsOn(shrinkRuntimeLibs)
    }
} else {
    tasks.named<Sync>("syncPortableDist") {
        from(layout.buildDirectory.dir("runtime-libs")) {
            into("runtime-libs")
        }
        dependsOn("syncRuntimeLibs")
    }
}

tasks.named<Zip>("portableDistZip") {
    eachFile {
        if (relativePath.pathString == "backend-standalone/kast-standalone") {
            permissions { unix("755") }
        }
    }
}
