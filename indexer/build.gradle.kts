import org.gradle.jvm.tasks.Jar

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.indexer-host")
}

extra["kastIncludeShadowJar"] = "false"

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val indexerIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val indexerPluginRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.slf4j", module = "slf4j-api")
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/indexer-idea-distributions/$ideaDistributionVersion")))
}

val extractIdeaDistribution: TaskProvider<ExtractIdeaDistributionTask> by tasks.registering(
    ExtractIdeaDistributionTask::class,
) {
    description = "Extracts the matched IDEA distribution for the isolated indexer host."
    archives.from(indexerIdeaDistribution)
    ideaVersion.set(ideaDistributionVersion)
    outputDirectory.set(extractedIdeaDistributionDirectory)
}

private fun extractedIdeaFiles(
    configure: ConfigurableFileTree.() -> Unit,
) = files(
    extractedIdeaDistributionDirectory.map { directory ->
        fileTree(directory) { configure() }
    },
).builtBy(extractIdeaDistribution)

val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
}

val ideaCompileLibs = ideaLibs.filter { library ->
    !library.name.startsWith("intellij.libraries.kotlinx.serialization") &&
        library.name != "intellij.libraries.ktor.utils.jar"
}

application {
    applicationName = "kast-indexer"
    mainClass = "io.github.amichne.kast.indexer.KastIndexerMainKt"
}

dependencies {
    implementation(project(":runtime:composition"))

    indexerIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaCompileLibs)
    indexerPluginRuntime(project(":runtime:composition"))
    testImplementation(ideaCompileLibs)
    testRuntimeOnly(ideaLibs)
}

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val launcherClassEntries = listOf(
    "io/github/amichne/kast/indexer/KastIndexerMainKt.class",
    "io/github/amichne/kast/indexer/KastIndexerBootstrap.class",
    "io/github/amichne/kast/indexer/IndexerBootstrap*.class",
    "io/github/amichne/kast/indexer/IdeaHomeAdmission*.class",
)

val indexerLauncherJar by tasks.registering(Jar::class) {
    description = "Builds the platform-bootstrap-only indexer launcher jar."
    archiveClassifier.set("launcher")
    from(sourceSets.named("main").map { it.output }) {
        include(launcherClassEntries)
    }
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = "kast-indexer-launcher"
        attributes["Implementation-Version"] = buildVersion.get()
    }
    isZip64 = true
}

val indexerPluginJar by tasks.registering(Jar::class) {
    description = "Builds the private Kast IntelliJ plugin payload jar."
    archiveClassifier.set("plugin")
    from(sourceSets.named("main").map { it.output }) {
        exclude(launcherClassEntries)
    }
    manifest {
        attributes["Implementation-Title"] = "kast-indexer"
        attributes["Implementation-Version"] = buildVersion.get()
    }
    isZip64 = true
}

val syncIndexerPluginPayload by tasks.registering(Sync::class) {
    description = "Stages private non-platform JAR inputs for the standalone IDE plugin owner."
    from(indexerPluginJar)
    from(indexerPluginRuntime)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(layout.buildDirectory.dir("plugin-payload"))
}

tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-indexer"))
    scriptContent.set(
        providers.fileContents(layout.projectDirectory.file("src/main/scripts/kast-indexer"))
            .asText
            .map(String::trimEnd),
    )
}

val indexerRuntimeRequiredClassEntries = listOf(
    "io/github/amichne/kast/indexer/KastIndexerMainKt.class",
    "io/github/amichne/kast/indexer/KastIndexerBootstrap.class",
)

tasks.named<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    dependsOn(indexerLauncherJar)
    appJar.set(indexerLauncherJar.flatMap(Jar::getArchiveFile))
    runtimeJars.setFrom()
    requiredClassEntries.addAll(indexerRuntimeRequiredClassEntries)
}

val indexerPluginRequiredClassEntries = listOf(
    "io/github/amichne/kast/indexer/KastIndexerApplicationStarter.class",
    "io/github/amichne/kast/indexer/InstalledIndexerTransport.class",
    "io/github/amichne/kast/runtime/composition/InstalledKastRuntime.class",
    "io/github/amichne/kast/workspace/intellij/provenance/GradleSourceRootProducerEvidence.class",
    "io/github/amichne/kast/workspace/intellij/provenance/GradleSourceRootProducerModel.class",
    "io/github/amichne/kast/workspace/intellij/provenance/GradleSourceRootProducerModelBuilder.class",
    "io/github/amichne/kast/workspace/intellij/provenance/GradleSourceRootProducerModelEntry.class",
    "META-INF/services/org.jetbrains.plugins.gradle.tooling.ModelBuilderService",
    "io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.class",
    "io/github/amichne/kast/workspace/intellij/provenance/KastGradleSourceRootProvenanceResolver.class",
    "io/github/amichne/kast/symbol/intellij/InstalledIntellijSymbolPorts.class",
    "io/github/amichne/kast/change/intellij/InstalledIntellijChangePorts.class",
    "io/github/amichne/kast/evidence/sqlite/SqliteWorkspacePublicationDatabase.class",
)

val platformKotlinPluginOwnedClassEntries = listOf(
    "org/jetbrains/kotlin/cli/common/arguments/Freezable.class",
    "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
)

tasks.named<Sync>("syncPortableDist") {
    from(layout.buildDirectory.dir("runtime-libs")) {
        into("runtime-libs")
    }
    from(indexerPluginJar) {
        into("private-plugins/kast-indexer/lib")
    }
    from(indexerPluginRuntime) {
        into("private-plugins/kast-indexer/lib")
    }
    dependsOn("syncRuntimeLibs", indexerPluginJar)
}

val verifyPortableDistLayout by tasks.registering(VerifyClasspathLayoutTask::class) {
    group = "verification"
    description = "Verifies the installed IntelliJ launcher and private Kast plugin split."
    dependsOn("syncPortableDist")

    val portableDist = layout.buildDirectory.dir("portable-dist/${project.name}")
    val runtimeLibs = portableDist.map { it.dir("runtime-libs") }
    val pluginLibs = portableDist.map { it.dir("private-plugins/kast-indexer/lib") }
    val platformKotlinLibs = extractedIdeaDistributionDirectory.map {
        it.dir("plugins/Kotlin/lib")
    }
    portableDistDirectory.set(portableDist)
    runtimeLibsDirectory.set(runtimeLibs)
    runtimeClasspathFile.set(runtimeLibs.map { it.file("classpath.txt") })
    pluginLibsDirectory.set(pluginLibs)
    platformPluginLibsDirectory.set(platformKotlinLibs)
    forbiddenPortableDistJarSuffixes.set(listOf("-all.jar"))
    forbiddenRuntimeJarPrefixes.set(listOf("composition-", "indexer-${project.version}-plugin"))
    forbiddenPluginClassEntries.set(platformKotlinPluginOwnedClassEntries)
    requiredRuntimeClassEntries.set(indexerRuntimeRequiredClassEntries)
    requiredPluginJarPrefixes.set(listOf("composition-", "indexer-"))
    requiredPluginClassEntries.set(indexerPluginRequiredClassEntries)
    requiredPlatformPluginClassEntries.set(platformKotlinPluginOwnedClassEntries)
    allowedPluginDescriptorJarPrefixes.set(listOf("indexer-"))
    dependsOn(extractIdeaDistribution)
}

val testIndexerLauncherIsolation by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves that each exact endpoint owns isolated IntelliJ process paths."
    val launcher = layout.projectDirectory.file("src/main/scripts/kast-indexer")
    val testScript = layout.projectDirectory.file(
        "src/test/scripts/test-kast-indexer-isolation.sh",
    )
    inputs.file(launcher)
    inputs.file(testScript)
    commandLine("bash", testScript, launcher)
}

tasks.named("check") {
    dependsOn(verifyPortableDistLayout, testIndexerLauncherIsolation)
}

tasks.named<Zip>("portableDistZip") {
    eachFile {
        if (relativePath.pathString == "indexer/kast-indexer") permissions { unix("755") }
    }
}

tasks.named<Jar>("jar") {
    isZip64 = true
}
