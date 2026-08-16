plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.diagnostic"

base {
    archivesName.set("diagnostic-service")
}

dependencies {
    implementation(project(":diagnostic:contract"))
    implementation(project(":workspace:contract"))
}
