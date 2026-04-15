import java.util.zip.ZipFile
import java.util.jar.JarInputStream
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
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
        id = "io.github.amichne.kast.intellij"
        name = "Kast Analysis Backend"
        version = project.version.toString()
        description = "Kast Kotlin analysis backend for IntelliJ IDEA"

        ideaVersion {
            sinceBuild = "253"   // IntelliJ 2025.3
        }
    }
}

val writeBackendVersion by tasks.registering {
    val versionFile = layout.buildDirectory.file("generated-resources/kast-backend-version.txt")
    outputs.file(versionFile)
    doLast {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(project.version.toString())
        }
    }
}

sourceSets.main {
    resources.srcDir(writeBackendVersion.map { it.outputs.files.singleFile.parentFile })
}

tasks.register("verifyPluginXmlPresent") {
    dependsOn(tasks.named("buildPlugin"))
    doLast {
        val distDir = layout.buildDirectory.dir("distributions").get().asFile
        val pluginZip = distDir.listFiles()?.firstOrNull { it.name.endsWith(".zip") }
            ?: error("No plugin zip found in $distDir")

        val content = ZipFile(pluginZip).use { zipFile ->
            zipFile.readPluginXmlContent()
        }
        check("KastPluginService" in content) { "plugin.xml is missing KastPluginService extension" }
        check("KastStartupActivity" in content) { "plugin.xml is missing KastStartupActivity extension" }
        check("org.jetbrains.kotlin" in content) { "plugin.xml is missing Kotlin plugin dependency" }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("idea.home.path", layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
}

configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

private fun ZipFile.readPluginXmlContent(): String {
    entries().asSequence()
        .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
        ?.let { entry -> return getInputStream(entry).bufferedReader().use { reader -> reader.readText() } }

    entries().asSequence()
        .filter { entry -> !entry.isDirectory && entry.name.endsWith(".jar") }
        .forEach { jarEntry ->
            JarInputStream(getInputStream(jarEntry)).use { jarStream ->
                generateSequence { jarStream.nextJarEntry }
                    .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                    ?.let {
                        return jarStream.bufferedReader().use { reader -> reader.readText() }
                    }
            }
        }

    error("plugin.xml not found in ${name}")
}
