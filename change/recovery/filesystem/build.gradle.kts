plugins {
    id("kast.kotlin-library")
    id("kast.role.filesystem-write")
}

group = "${rootProject.group}.change.recovery"

base {
    archivesName.set("change-recovery-filesystem")
}

dependencies {
    implementation(project(":change:recovery:contract"))
    implementation(project(":change:recovery:spi"))
}
