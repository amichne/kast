import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-write")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-intellij")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val ideaDistributionVersion = catalog.findVersion("idea-indexer").get().requiredVersion

val changeIdeaDistribution: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

private val extractedIdeaDistributionDirectory =
    objects.directoryProperty().apply {
        set(file(gradle.gradleUserHomeDir.resolve("kast/change-intellij-idea-distributions/$ideaDistributionVersion")))
    }

val extractChangeIdeaDistribution by
    tasks.registering(ExtractIdeaDistributionTask::class) {
        archives.from(changeIdeaDistribution)
        ideaVersion.set(ideaDistributionVersion)
        outputDirectory.set(extractedIdeaDistributionDirectory)
    }

private fun extractedIdeaFiles(configure: ConfigurableFileTree.() -> Unit) =
    files(
            extractedIdeaDistributionDirectory.map { directory ->
                fileTree(directory) {
                    configure()
                }
            }
        )
        .builtBy(extractChangeIdeaDistribution)

private val ideaLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/lib/**/*.jar")
    exclude("**/plugins/**")
}

private val kotlinPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/Kotlin/lib/**/*.jar")
    exclude("**/plugins/Kotlin/lib/jps/**")
    exclude("**/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
}

private val javaPluginLibs: ConfigurableFileCollection = extractedIdeaFiles {
    include("**/plugins/java/lib/**/*.jar")
}

dependencies {
    implementation(project(":protocol:contract"))
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":change:verify"))
    implementation(project(":evidence:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:intellij-read"))

    changeIdeaDistribution("com.jetbrains.intellij.idea:ideaIC:$ideaDistributionVersion@zip") {
        isTransitive = false
    }
    compileOnly(ideaLibs)
    compileOnly(kotlinPluginLibs)
    compileOnly(javaPluginLibs)
}

// Deliberately separate from main: the native acceptance probe is never shipped in the product plugin.
val nativeFixture by sourceSets.creating
val nativeFixtureTest by sourceSets.creating

kotlin.target.compilations
    .getByName("nativeFixtureTest")
    .associateWith(kotlin.target.compilations.getByName("nativeFixture"))

configurations[nativeFixtureTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

configurations[nativeFixtureTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(nativeFixture.compileOnlyConfigurationName, ideaLibs)
    add(nativeFixture.compileOnlyConfigurationName, kotlinPluginLibs)
    add(nativeFixture.compileOnlyConfigurationName, javaPluginLibs)
    add(nativeFixture.implementationConfigurationName, catalog.findLibrary("serialization-json").get())
    add(nativeFixtureTest.implementationConfigurationName, nativeFixture.output)
    add(nativeFixtureTest.implementationConfigurationName, catalog.findLibrary("serialization-json").get())
}

val nativeFixtureJar by
    tasks.registering(Jar::class) {
        group = "verification"
        description = "Builds the isolated-IDE test probe only; never a production plugin dependency."
        archiveBaseName.set("kast-native-fixture-probe")
        archiveVersion.set("")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(nativeFixture.output)
    }

tasks.register<Test>("nativeFixtureTest") {
    group = "verification"
    description = "Checks the native test probe's bounded request and state contracts without an IDE."
    testClassesDirs = nativeFixtureTest.output.classesDirs
    classpath = nativeFixtureTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.register<Zip>("nativeFixturePlugin") {
    group = "verification"
    description = "Packages an optional sandbox-only native acceptance probe; excluded from release assembly."
    archiveFileName.set("kast-native-fixture-probe.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    into("kast-native-fixture-probe/lib") {
        from(nativeFixtureJar)
        from(configurations[nativeFixture.runtimeClasspathConfigurationName]) {
            include("kotlinx-serialization-core-jvm-*.jar", "kotlinx-serialization-json-jvm-*.jar")
        }
    }
}
