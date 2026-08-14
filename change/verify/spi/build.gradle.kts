plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.change.verify"

base {
    archivesName.set("change-verify-spi")
}

dependencies {
    api(project(":change:contract"))
    api(project(":change:journal:contract"))
    api(project(":workspace:contract"))
}
