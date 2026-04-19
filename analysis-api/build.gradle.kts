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
    mainClass.set("io.github.amichne.kast.api.docs.OpenApiDocumentKt")
    val outputFile = rootProject.layout.projectDirectory.file("docs/openapi.yaml")
    args(outputFile.asFile.absolutePath)
}

tasks.register<JavaExec>("generateDocPages") {
    description = "Generates Markdown capability and API reference pages from the model registry"
    group = "documentation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.amichne.kast.api.docs.DocsDocumentKt")
    val outputDir = rootProject.layout.projectDirectory.dir("docs/reference")
    args(outputDir.asFile.absolutePath)
}
