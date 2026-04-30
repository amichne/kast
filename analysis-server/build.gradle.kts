plugins {
    id("kast.kotlin-library")
}

dependencies {
    api(project(":analysis-api"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)
    testImplementation(project(":shared-testing"))
}

tasks.register<JavaExec>("generateDocExamples") {
    description = "Generates example request/response JSON for each API operation"
    group = "documentation"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.server.DocExampleGeneratorKt")
    val outputDir = rootProject.layout.projectDirectory.dir("docs/examples")
    args(outputDir.asFile.absolutePath)
    dependsOn("testClasses")
}
