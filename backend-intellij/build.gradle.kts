import java.util.zip.ZipFile
import java.util.jar.JarInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

abstract class WriteBackendVersionTask : DefaultTask() {
    @get:Input
    abstract val backendVersion: Property<String>

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    @TaskAction
    fun write() {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(backendVersion.get())
        }
    }
}

abstract class VerifyPluginXmlPresentTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionsDirectory: DirectoryProperty

    @get:Input
    abstract val expectedPluginId: Property<String>

    @get:Input
    abstract val rejectedPluginId: Property<String>

    @TaskAction
    fun verify() {
        val distDir = distributionsDirectory.get().asFile
        val pluginZip = distDir.listFiles()?.firstOrNull { it.name.endsWith(".zip") }
            ?: error("No plugin zip found in $distDir")

        val content = ZipFile(pluginZip).use { zipFile ->
            zipFile.entries().asSequence()
                .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                ?.let { entry -> zipFile.getInputStream(entry).bufferedReader().use { reader -> reader.readText() } }
                ?: zipFile.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.endsWith(".jar") }
                    .mapNotNull { jarEntry ->
                        JarInputStream(zipFile.getInputStream(jarEntry)).use { jarStream ->
                            generateSequence { jarStream.nextJarEntry }
                                .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                                ?.let {
                                    jarStream.bufferedReader().use { reader -> reader.readText() }
                                }
                        }
                    }
                    .firstOrNull()
                ?: error("plugin.xml not found in ${pluginZip.name}")
        }

        val expectedIdTag = "<id>${expectedPluginId.get()}</id>"
        val rejectedIdTag = "<id>${rejectedPluginId.get()}</id>"
        check("KastPluginService" in content) { "plugin.xml is missing KastPluginService extension" }
        check("KastStartupActivity" in content) { "plugin.xml is missing KastStartupActivity extension" }
        check("org.jetbrains.kotlin" in content) { "plugin.xml is missing Kotlin plugin dependency" }
        check(expectedIdTag in content) {
            "plugin.xml must keep production plugin ID ${expectedPluginId.get()}"
        }
        check(rejectedIdTag !in content) {
            "plugin.xml contains rejected plugin ID ${rejectedPluginId.get()}"
        }
    }
}

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(project(":backend-shared"))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)

    intellijPlatform {
        intellijIdea("2025.3")
//        jetbrainsRuntime()
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.amichne.kast"
        name = "Kast Analysis Backend"
        version = project.version.toString()
        description = "Kast Kotlin analysis backend for IntelliJ IDEA"

        ideaVersion {
            sinceBuild = "253"   // IntelliJ 2025.3
        }
    }
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
    rejectedPluginId.set("io.github.amichne.kast.intellij")
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
    systemProperty("idea.home.path", layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
}

configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
