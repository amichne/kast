plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.symbol"

base {
    archivesName.set("symbol-service")
}

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
}
