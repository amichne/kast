plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.change.plan"

base {
    archivesName.set("change-plan-spi")
}

dependencies {
    api(project(":change:contract"))
    api(project(":workspace:contract"))
}
