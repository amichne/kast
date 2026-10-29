import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins {
    id("kast.runtime-serialization-app")
    id("kast.role.cli")
    id("org.graalvm.buildtools.native") version "1.1.14"
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

application {
    applicationName = "kast"
    mainClass = "io.github.amichne.kast.distribution.cli.KastManagementMainKt"
}

dependencies {
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":evidence:sqlite"))
    implementation(libs.clikt.core)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") { useJUnitPlatform() }

val generateVersion =
    tasks.register<Copy>("generateManagementVersion") {
        from("src/main/templates/ManagementVersion.kt.in")
        into(
            layout.buildDirectory.dir(
                "generated/sources/management-version/kotlin/io/github/amichne/kast/distribution/cli"
            )
        )
        val managementVersion = project.version.toString()
        inputs.property("managementVersion", managementVersion)
        expand("version" to managementVersion)
        rename { "ManagementVersion.kt" }
    }

kotlin.sourceSets.main {
    kotlin.srcDir(
        files(layout.buildDirectory.dir("generated/sources/management-version/kotlin")).builtBy(generateVersion)
    )
}

tasks.named("compileKotlin") { dependsOn(generateVersion) }

graalvmNative {
    val nativeImageLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        nativeImageCapable.set(true)
    }
    binaries {
        configureEach { javaLauncher.set(nativeImageLauncher) }
        named("main") {
            imageName.set("kast")
            mainClass.set(application.mainClass)
            sharedLibrary.set(false)
            fallback.set(false)
            buildArgs.addAll(providers.fileContents(layout.projectDirectory.file("native-image.args")).asText.map { it.lines().filter(String::isNotBlank) })
            if (providers.systemProperty("os.name").get().startsWith("Mac")) {
                buildArgs.addAll(providers.fileContents(layout.projectDirectory.file("native-image-macos.args")).asText.map { it.lines().filter(String::isNotBlank) })
            }
            if (providers.gradleProperty("kastNativeBuildMetrics").orNull == "true") {
                val metrics = providers.provider { repositoryRoot.file("build/reports/native/build-output.json") }
                tasks.named("nativeCompile") {
                    outputs.file(metrics).withPropertyName("nativeBuildMetrics")
                }
                buildArgs.add("-H:+UnlockExperimentalVMOptions")
                buildArgs.add(metrics.map { "-H:BuildOutputJSONFile=${it.asFile.absolutePath}" })
                buildArgs.add("-H:-UnlockExperimentalVMOptions")
            }
        }
    }
}

// Recovery never executes an unqualified script from the installation being erased.
tasks.named<ProcessResources>("processResources") {
    from(repositoryRoot.file("install.sh")) {
        into("management")
        rename { "bootstrap-install.sh" }
    }
}

val importedManagementExecutable = providers.gradleProperty("kastNativeExecutable")
configurations.consumable("managementExecutableElements") {
    if (importedManagementExecutable.isPresent) {
        outgoing.artifact(layout.file(importedManagementExecutable.map { file(it) }))
    } else {
        outgoing.artifact(layout.buildDirectory.file("native/nativeCompile/kast")) {
            builtBy(tasks.named("nativeCompile"))
        }
    }
}

val nativeImageOptions = tasks.register<WriteProperties>("nativeImageOptions") {
    destinationFile.set(layout.buildDirectory.file("native-inputs/image.properties"))
    property("mainClass", application.mainClass)
}

tasks.register<Tar>("assembleNativeImageInputs") {
    group = "distribution"
    description = "Packages compiled JVM inputs for native compilation on the target host."
    dependsOn(tasks.named("installDist"), nativeImageOptions)
    from(tasks.named<Sync>("installDist").map { it.destinationDir }) { include("lib/**") }
    from(nativeImageOptions.flatMap { it.destinationFile })
    from("native-image.args", "native-image-macos.args")
    archiveFileName.set("native-inputs.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
