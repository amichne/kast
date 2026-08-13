plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.change.recovery"

base {
    archivesName.set("change-recovery-spi")
}

dependencies {
    api(project(":change:recovery:contract"))
}
