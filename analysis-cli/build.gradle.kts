plugins {
    id("kas.standalone-app")
}

application {
    mainClass = "io.github.amichne.kast.cli.CliMainKt"
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(project(":backend-standalone"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.logback.classic)
}
