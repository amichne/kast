plugins {
    id("kast.kotlin-library")
    id("kast.kotlin-serialization")
}

dependencies {
    implementation(libs.coroutines.core)
}

tasks.register<JavaExec>("generateOpenApiSpec") {
    description = "Generates the OpenAPI 3.1 YAML specification for the analysis API"
    group = "documentation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.api.AnalysisOpenApiDocumentKt")
    val outputFile = rootProject.layout.projectDirectory.file("docs/openapi.yaml")
    args(outputFile.asFile.absolutePath)
}
