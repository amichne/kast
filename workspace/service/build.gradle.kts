plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-service")
}

dependencies {
    implementation(project(":evidence:contract"))
    implementation(project(":workspace:contract"))
}
