import org.gradle.jvm.tasks.Jar

plugins {
    id("kast.runtime-serialization-app")
}

extra["kastIncludeShadowJar"] = "false"

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea").get().requiredVersion

val ideaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/headless-idea-distributions/$ideaDistributionVersion")))

}

val extractIdeaDistribution: TaskProvider<ExtractIdeaDistributionTask> by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(ideaDistribution)
    ideaVersion.set(ideaDistributionVersion)
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

val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
    exclude("**/testFramework.jar")
    exclude("**/testFramework-k1.jar")
}

val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

val gradlePluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("plugins/gradle/lib/*.jar")
    include("plugins/gradle/lib/**/*.jar")
    include("plugins/gradle-java/lib/*.jar")
    include("plugins/gradle-java/lib/**/*.jar")
}

val headlessIdeaHomeProfile = providers.gradleProperty("kastHeadlessIdeaHomeProfile")
    .orElse("full")
    .map { it.lowercase() }

val fullPackagedIdeaHomeEntries = listOf(
    "build.txt",
    "product-info.json",
    "lib/nio-fs.jar",
    "lib/jna/**",
    "lib/pty4j/**",
    "modules/module-descriptors.dat",
    "plugins/Groovy/**",
    "plugins/Kotlin/**",
    "plugins/gradle/**",
    "plugins/gradle-java/**",
    "plugins/java/**",
    "plugins/java-ide-customization/**",
    "plugins/json/**",
    "plugins/maven/**",
    "plugins/properties/**",
    "plugins/repository-search/**",
    "plugins/toml/**",
    "plugins/yaml/**",
)

val minimalPackagedIdeaHomeEntries = listOf(
    "build.txt",
    "product-info.json",
    "lib/nio-fs.jar",
    "lib/jna/**",
    "lib/pty4j/**",
    "modules/module-descriptors.dat",
    "plugins/gradle/**",
    "plugins/gradle-java/**",
    "plugins/java/**",
    "plugins/Kotlin/**",
)

val agentPackagedIdeaHomeEntries = minimalPackagedIdeaHomeEntries + listOf(
    "plugins/gradle/**",
    "plugins/gradle-java/**",
    "plugins/java-ide-customization/**",
    "plugins/json/**",
    "plugins/maven/**",
    "plugins/properties/**",
    "plugins/repository-search/**",
    "plugins/toml/**",
    "plugins/yaml/**",
)

val packagedIdeaHomeEntries = when (headlessIdeaHomeProfile.get()) {
    "full" -> fullPackagedIdeaHomeEntries
    "minimal" -> minimalPackagedIdeaHomeEntries
    "agent" -> agentPackagedIdeaHomeEntries
    else -> error("Unsupported kastHeadlessIdeaHomeProfile=${headlessIdeaHomeProfile.get()}")
}

val headlessPluginRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.slf4j", module = "slf4j-api")
}

val backendIdeaPluginArtifacts: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

private val backendIdeaHeadlessRuntimeCapability =
    "${project.group}:backend-idea-headless-runtime"

application {
    mainClass = "io.github.amichne.kast.headless.HeadlessMainKt"
}

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val headlessLauncherJar by tasks.registering(Jar::class) {
    archiveClassifier.set("launcher")
    from(sourceSets.named("main").map { it.output }) {
        exclude("META-INF/plugin.xml")
    }
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Title"] = "${project.name}-launcher"
        attributes["Implementation-Version"] = buildVersion.get()
    }
    isZip64 = true
}

val headlessPluginDescriptorJar by tasks.registering(Jar::class) {
    archiveClassifier.set("plugin-descriptor")
    from(sourceSets.named("main").map { it.output }) {
        include("META-INF/plugin.xml")
    }
    manifest {
        attributes["Implementation-Title"] = "${project.name}-plugin-descriptor"
        attributes["Implementation-Version"] = buildVersion.get()
    }
}

val headlessPluginImplementationJar by tasks.registering(Jar::class) {
    archiveClassifier.set("plugin")
    from(sourceSets.named("main").map { it.output }) {
        exclude("META-INF/plugin.xml")
    }
    manifest {
        attributes["Implementation-Title"] = "${project.name}-plugin"
        attributes["Implementation-Version"] = buildVersion.get()
    }
}

val headlessBackendIdeaRuntimeJar by tasks.registering(Jar::class) {
    archiveBaseName.set("backend-idea")
    archiveVersion.set(buildVersion)
    archiveClassifier.set("headless-runtime")
    val backendIdeaBaseJar = providers.provider {
        backendIdeaPluginArtifacts.files.single { artifact ->
            artifact.name.startsWith("backend-idea-") && artifact.name.endsWith("-base.jar")
        }
    }
    from(backendIdeaBaseJar.map { artifact -> zipTree(artifact) }) {
        exclude("META-INF/plugin.xml")
    }
    dependsOn(backendIdeaPluginArtifacts)
    isZip64 = true
}

val writeBackendVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated-resources/kast-backend-version.txt")
    val versionProvider = buildVersion
    inputs.property("buildVersion", versionProvider)
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
    ideaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly(project(":analysis-api"))
    compileOnly(project(":analysis-server"))
    compileOnly(project(":backend-idea"))
    compileOnly(project(":backend-shared"))
    compileOnly(project(":index-store"))
    implementation(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
    compileOnly(gradlePluginLibs)
    compileOnly(libs.coroutines.core)

    headlessPluginRuntime(project(":analysis-api"))
    headlessPluginRuntime(project(":analysis-server"))
    headlessPluginRuntime(project(":backend-idea")) {
        capabilities {
            requireCapability(backendIdeaHeadlessRuntimeCapability)
        }
    }
    headlessPluginRuntime(project(":backend-shared"))
    headlessPluginRuntime(project(":index-store"))
    headlessPluginRuntime(libs.coroutines.core)

    backendIdeaPluginArtifacts(project(":backend-idea")) {
        capabilities {
            requireCapability(backendIdeaHeadlessRuntimeCapability)
        }
    }

    testImplementation(project(":analysis-api"))
    testImplementation(project(":backend-idea")) {
        capabilities {
            requireCapability(backendIdeaHeadlessRuntimeCapability)
        }
    }
    testImplementation(gradlePluginLibs)
}

tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-headless"))
    scriptContent.set(
        providers.fileContents(layout.projectDirectory.file("src/main/scripts/kast-headless"))
            .asText
            .map { content -> content.trimEnd() },
    )
}

val headlessRuntimeRequiredClassEntries = listOf(
    "io/github/amichne/kast/headless/HeadlessMainKt.class",
    "com/intellij/idea/Main.class",
    "com/intellij/openapi/application/ApplicationStarter.class",
    "com/intellij/openapi/project/DumbService.class",
)

tasks.named<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    dependsOn(headlessLauncherJar)
    appJar.set(headlessLauncherJar.flatMap(Jar::getArchiveFile))
    requiredClassEntries.addAll(headlessRuntimeRequiredClassEntries)
}

val headlessPluginRequiredClassEntries = listOf(
    "io/github/amichne/kast/headless/HeadlessApplicationStarter.class",
    "io/github/amichne/kast/api/client/ServerLaunchOptions.class",
    "io/github/amichne/kast/server/AnalysisServer.class",
    "io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.class",
    "io/github/amichne/kast/shared/analysis/PsiReferenceScanner.class",
    "io/github/amichne/kast/idea/KastIdeaBackendRuntime.class",
)

val headlessPluginRuntimeJarPrefixes = listOf(
    "analysis-api-",
    "analysis-server-",
    "backend-idea-",
    "backend-shared-",
    "index-store-",
    "kotlinx-coroutines-core",
)

val headlessPluginLibJarPrefixes = headlessPluginRuntimeJarPrefixes

tasks.named<Sync>("syncPortableDist") {
    from(layout.buildDirectory.dir("runtime-libs")) {
        into("runtime-libs")
    }
    from(extractedIdeaDistributionDirectory) {
        include(packagedIdeaHomeEntries)
        into("idea-home")
    }
    from(headlessPluginDescriptorJar) {
        into("idea-home/plugins/kast-headless/lib")
    }
    from(headlessPluginImplementationJar) {
        into("idea-home/plugins/kast-headless/lib")
    }
    from(headlessPluginRuntime.filter { artifact -> !artifact.name.startsWith("backend-idea-") }) {
        into("idea-home/plugins/kast-headless/lib")
    }
    from(headlessBackendIdeaRuntimeJar) {
        into("idea-home/plugins/kast-headless/lib")
    }
    dependsOn("syncRuntimeLibs")
    dependsOn(extractIdeaDistribution)
}

val verifyHeadlessPortableDistLayout by tasks.registering(VerifyClasspathLayoutTask::class) {
    group = "verification"
    description = "Verifies headless plugin runtime jars are loaded from the plugin class loader."
    dependsOn("syncPortableDist")

    val portableDistDirectory = layout.buildDirectory.dir("portable-dist/${project.name}")
    val runtimeLibsDirectory = layout.buildDirectory.dir("portable-dist/${project.name}/runtime-libs")
    val pluginLibsDirectory = layout.buildDirectory.dir("portable-dist/${project.name}/idea-home/plugins/kast-headless/lib")
    this.portableDistDirectory.set(portableDistDirectory)
    this.runtimeLibsDirectory.set(runtimeLibsDirectory)
    runtimeClasspathFile.set(runtimeLibsDirectory.map { it.file("classpath.txt") })
    this.pluginLibsDirectory.set(pluginLibsDirectory)
    forbiddenPortableDistJarSuffixes.set(listOf("-all.jar"))
    forbiddenRuntimeJarPrefixes.set(headlessPluginRuntimeJarPrefixes)
    requiredRuntimeClassEntries.set(headlessRuntimeRequiredClassEntries)
    requiredPluginJarPrefixes.set(headlessPluginLibJarPrefixes)
    requiredPluginClassEntries.set(headlessPluginRequiredClassEntries)
    allowedPluginDescriptorJarPrefixes.set(listOf("backend-headless-"))
}

tasks.named("check") {
    dependsOn(verifyHeadlessPortableDistLayout)
}

tasks.named<Zip>("portableDistZip") {
    eachFile {
        if (relativePath.pathString == "backend-headless/kast-headless") {
            permissions { unix("755") }
        }
    }
}
