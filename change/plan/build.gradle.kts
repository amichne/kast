plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-plan")
}

dependencies {
    implementation(project(":change:contract"))
}
