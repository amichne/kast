import VerifyPluginXmlPresentTask
import WriteBackendVersionTask
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.jvm.tasks.Jar
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("kast.idea-build-helpers")
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")

    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea").get().requiredVersion

val ideaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory = objects.directoryProperty().apply {
    set(file(gradle.gradleUserHomeDir.resolve("kast/backend-idea-distributions/$ideaDistributionVersion")))
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

val gradlePluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("plugins/gradle/lib/*.jar")
    include("plugins/gradle/lib/**/*.jar")
    include("plugins/gradle-java/lib/*.jar")
    include("plugins/gradle-java/lib/**/*.jar")
}

dependencies {
    ideaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }

    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(project(":backend-shared"))
    implementation(project(":index-store"))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.serialization.json)
    compileOnly(gradlePluginLibs)

    intellijPlatform {
        intellijIdea(ideaDistributionVersion)
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.plugins.gradle")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradlePluginLibs)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

val headlessRuntimeElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(
        configurations.named("implementation").get(),
        configurations.named("runtimeOnly").get(),
    )
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
    outgoing.artifact(tasks.named<Jar>("jar"))
    outgoing.capability("${project.group}:backend-idea-headless-runtime:${project.version}")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.amichne.kast"
        name = "Kast Analysis Backend"
        version = project.version.toString()
        description = "Kast Kotlin analysis backend for IDEA-based IDEs"

        ideaVersion {
            sinceBuild = "261"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.AndroidStudio, "2026.1.2.10")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")
val writeBackendVersion by tasks.registering(WriteBackendVersionTask::class) {
    backendVersion.set(version.toString())
    versionFile.set(generatedResourcesDir.map { it.file("kast-backend-version.txt") })
}

val defaultExcludedTestTags = linkedSetOf("concurrency", "performance", "parity")

fun parseTestTags(rawTags: String?): LinkedHashSet<String> =
    rawTags
        ?.split(",")
        ?.asSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toCollection(linkedSetOf())
        ?: linkedSetOf()

sourceSets.main {
    resources.srcDir(generatedResourcesDir)
}

tasks.named("processResources") {
    dependsOn(writeBackendVersion)
}

tasks.register<VerifyPluginXmlPresentTask>("verifyPluginXmlPresent") {
    dependsOn(tasks.named("buildPlugin"))
    distributionsDirectory.set(layout.buildDirectory.dir("distributions"))
    expectedPluginId.set("io.github.amichne.kast")
    rejectedPluginId.set("io.github.amichne.kast.idea")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        val includedTags = parseTestTags(providers.gradleProperty("includeTags").orNull)
        val excludedTags = linkedSetOf<String>().apply {
            if (includedTags.isEmpty()) {
                addAll(defaultExcludedTestTags)
            }
            addAll(parseTestTags(providers.gradleProperty("excludeTags").orNull))
        }
        if (excludedTags.isNotEmpty()) {
            excludeTags(*excludedTags.toTypedArray())
        }
        if (includedTags.isNotEmpty()) {
            includeTags(*includedTags.toTypedArray())
        }
    }
}

configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
