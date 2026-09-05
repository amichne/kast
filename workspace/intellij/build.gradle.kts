import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.workspace")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val workspaceIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/workspace-intellij-idea-distributions/$ideaDistributionVersion")))
}

val extractWorkspaceIdeaDistribution by tasks.registering(ExtractIdeaDistributionTask::class) {
    archives.from(workspaceIdeaDistribution)
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
).builtBy(extractWorkspaceIdeaDistribution)

private val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
}

private val gradlePluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/gradle*/lib/**/*.jar")
}

private val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

dependencies {
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(project(":workspace:contract"))
    implementation(project(":distribution:contract"))
    implementation(project(":workspace:intellij-read"))

    workspaceIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibs)
    compileOnly(gradlePluginLibs)
    compileOnly(javaPluginLibs)
    testImplementation(ideaLibs)
    testImplementation(gradlePluginLibs)
    testImplementation(javaPluginLibs)
    testImplementation(catalog.findLibrary("gradle-tooling-api").get())
}

// These classes execute inside the repository Gradle daemon, independently of the Java 25 IDE.
val gradleTooling by sourceSets.creating
configurations[gradleTooling.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
sourceSets.main {
    compileClasspath += gradleTooling.output
    runtimeClasspath += gradleTooling.output
}
sourceSets.test {
    compileClasspath += gradleTooling.output
    runtimeClasspath += gradleTooling.output
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileGradleToolingKotlin") {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=8")
    }
}
tasks.named<JavaCompile>("compileGradleToolingJava") {
    options.release.set(8)
}
val gradleToolingJar by tasks.registering(Jar::class) {
    archiveClassifier.set("gradle-tooling")
    from(gradleTooling.output)
}
// Gradle instruments every class in a tooling JAR, so Java 25 IDE classes must stay in their own JAR.
artifacts {
    add("apiElements", gradleToolingJar)
    add("runtimeElements", gradleToolingJar)
}
tasks.named<Jar>("sourcesJar") { from(gradleTooling.allSource) }
