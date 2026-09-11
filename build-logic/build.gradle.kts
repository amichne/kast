import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

abstract class JsonContractParserTestArguments : CommandLineArgumentProvider {
    @get:Classpath abstract val parserClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-Dkast.jsonContractParserClasspath=${parserClasspath.asPath}")
}

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Compile PSI scanning separately: KGP embeds relocated compiler classes that must not bind this code.
val jsonContracts by sourceSets.creating

kotlin.sourceSets.named(jsonContracts.name) {
    kotlin.srcDir("src/main/kotlin")
    kotlin.include("conventions/jsoncontracts/**")
}

kotlin.sourceSets.named("main") { kotlin.exclude("conventions/jsoncontracts/**") }

sourceSets.main {
    compileClasspath += jsonContracts.output
    runtimeClasspath += jsonContracts.output
}

sourceSets.test {
    compileClasspath += jsonContracts.output
    runtimeClasspath += jsonContracts.output
}

val jsonContractsTest by sourceSets.creating {
    compileClasspath += jsonContracts.output
    runtimeClasspath += jsonContracts.output
}

kotlin.sourceSets.named(jsonContractsTest.name) {
    kotlin.srcDir("src/test/kotlin")
    kotlin.include("JsonContractGuardTest.kt")
}

kotlin.sourceSets.named("test") { kotlin.exclude("JsonContractGuardTest.kt") }

configurations.named(jsonContractsTest.implementationConfigurationName) {
    extendsFrom(configurations.getByName(jsonContracts.implementationConfigurationName))
}

val jsonContractsTestTask =
    tasks.register<Test>("jsonContractsTest") {
        testClassesDirs = jsonContractsTest.output.classesDirs
        classpath = jsonContractsTest.runtimeClasspath
        useJUnitPlatform()
    }

tasks.jar { from(jsonContracts.output) }

dependencies {
    add(
        jsonContracts.implementationConfigurationName,
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:${catalog.findVersion("kotlin").get().requiredVersion}",
    )
    add(jsonContracts.implementationConfigurationName, catalog.findLibrary("serialization-json").get())
    add(jsonContractsTest.implementationConfigurationName, catalog.findLibrary("junit-jupiter").get())
    add(jsonContractsTest.runtimeOnlyConfigurationName, catalog.findLibrary("junit-platform-launcher").get())
    implementation(catalog.findLibrary("kotlin-gradle-plugin").get())
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:" + catalog.findVersion("kotlin").get().requiredVersion)
    implementation(catalog.findLibrary("kotlin-serialization-plugin").get())
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(catalog.findLibrary("json-schema-validator").get())
    implementation(catalog.findLibrary("vanniktech-maven-publish-plugin").get())
    implementation(
        "com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:${catalog.findVersion("shadow").get().requiredVersion}"
    )
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.10.2")
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(gradleTestKit())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    dependsOn(jsonContractsTestTask)
    jvmArgumentProviders.add(
        objects.newInstance<JsonContractParserTestArguments>().apply {
            parserClasspath.from(jsonContracts.runtimeClasspath)
        }
    )
    useJUnitPlatform()
}
