plugins {
    id("kast.kotlin-library")
    id("kast.role.filesystem-write")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":protocol:wire"))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging.otlp)
}
