plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.change.apply"

base {
    archivesName.set("change-apply-spi")
}

dependencies {
    api(project(":change:contract"))
    api(project(":change:recovery:contract"))
    api(project(":change:journal:contract"))
}
