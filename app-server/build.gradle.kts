import org.gradle.api.tasks.testing.Test

plugins {
    `java-test-fixtures`
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
    id("kast.role.app-server")
    id("kast.public-query-contract")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

dependencies {
    testFixturesApi(project(":distribution:contract"))
    testFixturesApi(project(":protocol:contract"))
    testFixturesImplementation(project(":kernel"))
    testFixturesImplementation(project(":distribution:managed"))
    testFixturesImplementation(libs.serialization.json)
    testImplementation(testFixtures(project(":distribution:managed")))
    testImplementation(libs.jimfs)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.json.schema.validator)
    implementation(libs.bundles.ktor.broker)
    implementation(project(":kernel"))
    implementation(project(":workspace:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
    implementation(project(":distribution:contract"))
    implementation(project(":distribution:managed"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
    implementation(project(":protocol:wire"))
}

tasks.named<Test>("test") {
    val installedSchemas = providers.environmentVariable("KAST_CODEX_SCHEMA_DIRECTORY")
    inputs.property("installedCodexSchemaDirectory", installedSchemas.orElse(""))
    inputs.dir(installedSchemas).optional().withPropertyName("installedCodexSchemas")
}

val installedWorkspaceHarnessClasspath = layout.buildDirectory.file("acceptance/installed-workspace-harness.classpath")

tasks.register("writeInstalledWorkspaceHarnessClasspath") {
    group = "verification"
    description = "Projects the test-only installed routing harness classpath; never ships it in the product."
    dependsOn(tasks.named("testClasses"))
    val harnessClasspath = sourceSets.test.get().runtimeClasspath
    val classpathOutput = installedWorkspaceHarnessClasspath
    inputs.files(harnessClasspath)
    outputs.file(classpathOutput)
    doLast {
        classpathOutput.get().asFile.apply {
            parentFile.mkdirs()
            writeText(harnessClasspath.filter { it.exists() }.asPath)
        }
    }
}

val verifyReleaseRuntimeAdmission =
    tasks.register<JavaExec>("verifyReleaseRuntimeAdmission") {
        group = "verification"
        dependsOn(tasks.named("testClasses"), ":stageKastControlProduct")
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "io.github.amichne.kast.appserver.ControlDistributionAdmissionMain"
        val product = providers.provider { repositoryRoot.dir("build/control-product") }
        inputs.dir(product)
        args(product.get().asFile.absolutePath)
    }
