import org.gradle.jvm.tasks.Jar

plugins {
    id("kast.runtime-app")
    kotlin("plugin.serialization")
}

extra["kastIncludeShadowJar"] = "false"

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion
private val ideaPlatformBuild = catalog.findVersion("idea-platform-build").get().requiredVersion

val indexerIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val indexerLauncherRuntime: Configuration by configurations.creating {
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
    archives.from(indexerIdeaDistribution)
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
}

val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

val gradlePluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/gradle*/lib/**/*.jar")
}

val indexerTestPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Groovy/lib/**/*.jar")
    include("**/plugins/Kotlin/lib/**/*.jar")
    include("**/plugins/gradle*/lib/**/*.jar")
    include("**/plugins/java/lib/**/*.jar")
    include("**/plugins/java-ide-customization/lib/**/*.jar")
    include("**/plugins/json/lib/**/*.jar")
    include("**/plugins/maven/lib/**/*.jar")
    include("**/plugins/properties/lib/**/*.jar")
    include("**/plugins/repository-search/lib/**/*.jar")
    include("**/plugins/toml/lib/**/*.jar")
    include("**/plugins/yaml/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
    exclude("**/plugins/Groovy/lib/*-jps.jar")
}

val indexerIdeaHomeProfile = providers.gradleProperty("kastIndexerIdeaHomeProfile")
    .orElse("full")
    .map(String::lowercase)

val minimalPackagedIdeaHomeEntries = listOf(
    "build.txt",
    "product-info.json",
    "lib/nio-fs.jar",
    "lib/jna/**",
    "lib/pty4j/**",
    "modules/module-descriptors.dat",
    "plugins/gradle*/**",
    "plugins/java/**",
    "plugins/Kotlin/**",
)

val agentPackagedIdeaHomeEntries = minimalPackagedIdeaHomeEntries + listOf(
    "plugins/java-ide-customization/**",
    "plugins/json/**",
    "plugins/maven/**",
    "plugins/properties/**",
    "plugins/repository-search/**",
    "plugins/toml/**",
    "plugins/yaml/**",
)

val fullPackagedIdeaHomeEntries = agentPackagedIdeaHomeEntries + listOf(
    "plugins/Groovy/**",
)

val indexerJvmArguments = listOf(
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
    "--add-opens=java.base/sun.net.dns=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.util=ALL-UNNAMED",
    "--add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
    "--add-opens=java.management/sun.management=ALL-UNNAMED",
    "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
    "--add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED",
)

val packagedIdeaHomeEntries = when (indexerIdeaHomeProfile.get()) {
    "full" -> fullPackagedIdeaHomeEntries
    "minimal" -> minimalPackagedIdeaHomeEntries
    "agent" -> agentPackagedIdeaHomeEntries
    else -> error("Unsupported kastIndexerIdeaHomeProfile=${indexerIdeaHomeProfile.get()}")
}

application {
    mainClass = "io.github.amichne.kast.indexer.KastIndexerMainKt"
}

@Suppress("UNCHECKED_CAST")
val buildVersion: Provider<String> = extra["buildVersion"] as Provider<String>

val generatedResourcesDirectory = layout.buildDirectory.dir("generated-resources")
val writeIndexerVersion by tasks.registering(WriteIndexerVersionTask::class) {
    indexerVersion.set(version.toString())
    versionFile.set(generatedResourcesDirectory.map { it.file("kast-indexer-version.txt") })
}

sourceSets.main {
    resources.srcDir(generatedResourcesDirectory)
}

tasks.named("processResources") {
    dependsOn(writeIndexerVersion)
}

dependencies {
    indexerIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    compileOnly(project(":analysis-api"))
    compileOnly(project(":analysis-server"))
    compileOnly(project(":index-store"))
    compileOnly(libs.coroutines.core)
    compileOnly(libs.opentelemetry.api)
    compileOnly(libs.opentelemetry.sdk)
    compileOnly(libs.serialization.json)
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
    compileOnly(gradlePluginLibs)

    indexerLauncherRuntime(ideaLibs)

    indexerPluginRuntime(project(":analysis-api"))
    indexerPluginRuntime(project(":analysis-server"))
    indexerPluginRuntime(project(":index-store"))
    indexerPluginRuntime(libs.coroutines.core)
    indexerPluginRuntime(libs.opentelemetry.api)
    indexerPluginRuntime(libs.opentelemetry.sdk)
    indexerPluginRuntime(libs.serialization.json)

    testImplementation(project(":analysis-api"))
    testImplementation(project(":analysis-server"))
    testImplementation(project(":index-store"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(ideaLibs)
    testImplementation(indexerTestPluginLibs)
    testImplementation("com.jetbrains.intellij.platform:test-framework:$ideaPlatformBuild")
    testImplementation("com.jetbrains.intellij.platform:test-framework-junit5:$ideaPlatformBuild")
}

tasks.withType<Test>().configureEach {
    dependsOn(extractIdeaDistribution)
    jvmArgs(indexerJvmArguments)
    systemProperty("idea.home.path", extractedIdeaDistributionDirectory.get().asFile.absolutePath)
    systemProperty(
        "idea.plugins.path",
        extractedIdeaDistributionDirectory.get().dir("plugins").asFile.absolutePath,
    )
    systemProperty("idea.platform.prefix", "Idea")
    systemProperty("idea.classpath.index.enabled", "false")
    systemProperty("idea.force.use.core.classloader", "true")
    systemProperty("intellij.testFramework.rethrow.logged.errors", "true")
    systemProperty("java.awt.headless", "true")
}

val launcherClassEntries = listOf(
    "io/github/amichne/kast/indexer/KastIndexerMainKt.class",
    "io/github/amichne/kast/indexer/KastIndexerBootstrap.class",
)

val indexerLauncherJar by tasks.registering(Jar::class) {
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

tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-indexer"))
    scriptContent.set(
        providers.fileContents(layout.projectDirectory.file("src/main/scripts/kast-indexer"))
            .asText
            .map { content -> content.trimEnd() },
    )
}

val indexerRuntimeRequiredClassEntries = launcherClassEntries + listOf(
    "com/intellij/idea/Main.class",
    "com/intellij/openapi/application/ApplicationStarter.class",
    "com/intellij/openapi/project/DumbService.class",
)

tasks.named<SyncRuntimeLibsTask>("syncRuntimeLibs") {
    dependsOn(indexerLauncherJar)
    appJar.set(indexerLauncherJar.flatMap(Jar::getArchiveFile))
    runtimeJars.from(indexerLauncherRuntime)
    requiredClassEntries.addAll(indexerRuntimeRequiredClassEntries)
}

val indexerPluginRequiredClassEntries = listOf(
    "io/github/amichne/kast/indexer/KastIndexerApplicationStarter.class",
    "io/github/amichne/kast/api/client/ServerLaunchOptions.class",
    "io/github/amichne/kast/server/AnalysisServer.class",
    "io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.class",
    "io/github/amichne/kast/shared/analysis/PsiReferenceScanner.class",
    "io/github/amichne/kast/idea/IndexerServerRuntime.class",
)

val platformKotlinPluginOwnedClassEntries = listOf(
    "org/jetbrains/kotlin/cli/common/arguments/Freezable.class",
    "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
)

val indexerPluginRuntimeJarPrefixes = listOf(
    "analysis-api-",
    "analysis-server-",
    "index-store-",
    "kotlinx-coroutines-core",
    "opentelemetry-",
)

tasks.named<Sync>("syncPortableDist") {
    from(layout.buildDirectory.dir("runtime-libs")) {
        into("runtime-libs")
    }
    from(extractedIdeaDistributionDirectory) {
        include(packagedIdeaHomeEntries)
        into("idea-home")
    }
    from(indexerPluginJar) {
        into("idea-home/plugins/kast-indexer/lib")
    }
    into("idea-home/plugins/kast-indexer/lib") {
        from(indexerPluginRuntime)
    }
    dependsOn("syncRuntimeLibs")
    dependsOn(extractIdeaDistribution)
}

val verifyPortableDistLayout by tasks.registering(VerifyClasspathLayoutTask::class) {
    group = "verification"
    description = "Verifies that indexer implementation jars use the private indexer classloader."
    dependsOn("syncPortableDist")

    val portableDistDirectory = layout.buildDirectory.dir("portable-dist/${project.name}")
    val runtimeLibsDirectory = portableDistDirectory.map { it.dir("runtime-libs") }
    val pluginLibsDirectory = portableDistDirectory.map {
        it.dir("idea-home/plugins/kast-indexer/lib")
    }
    val platformPluginLibsDirectory = portableDistDirectory.map {
        it.dir("idea-home/plugins/Kotlin/lib")
    }
    this.portableDistDirectory.set(portableDistDirectory)
    this.runtimeLibsDirectory.set(runtimeLibsDirectory)
    runtimeClasspathFile.set(runtimeLibsDirectory.map { it.file("classpath.txt") })
    this.pluginLibsDirectory.set(pluginLibsDirectory)
    this.platformPluginLibsDirectory.set(platformPluginLibsDirectory)
    forbiddenPortableDistJarSuffixes.set(listOf("-all.jar"))
    forbiddenRuntimeJarPrefixes.set(
        indexerPluginRuntimeJarPrefixes + "indexer-${project.version}-plugin",
    )
    forbiddenPluginClassEntries.set(platformKotlinPluginOwnedClassEntries)
    requiredRuntimeClassEntries.set(indexerRuntimeRequiredClassEntries)
    requiredPluginJarPrefixes.set(indexerPluginRuntimeJarPrefixes + "indexer-")
    requiredPluginClassEntries.set(indexerPluginRequiredClassEntries)
    requiredPlatformPluginClassEntries.set(platformKotlinPluginOwnedClassEntries)
    allowedPluginDescriptorJarPrefixes.set(listOf("indexer-"))
}

tasks.named("check") {
    dependsOn(verifyPortableDistLayout)
}

tasks.named<Zip>("portableDistZip") {
    eachFile {
        if (relativePath.pathString == "indexer/kast-indexer") {
            permissions { unix("755") }
        }
    }
}
