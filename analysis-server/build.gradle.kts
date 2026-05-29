plugins {
    id("kast.published-library")
}

kastPublishing {
    artifactId.set("kast-analysis-server")
    moduleName.set("Kast Analysis Server")
    moduleDescription.set("JSON-RPC dispatch, descriptor lifecycle, and local analysis-server transports for Kast backends.")
}

dependencies {
    api(project(":analysis-api"))
    implementation(project(":index-store"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":analysis-api")))
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
