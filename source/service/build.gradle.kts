plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.source"

base {
    archivesName.set("source-service")
}

dependencies {
    implementation(project(":source:contract"))
    implementation(project(":workspace:contract"))
}
