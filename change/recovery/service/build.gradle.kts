plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change.recovery"

base {
    archivesName.set("change-recovery-service")
}

dependencies {
    implementation(project(":change:journal:contract"))
    implementation(project(":change:recovery:contract"))
    implementation(project(":change:recovery:spi"))
}
