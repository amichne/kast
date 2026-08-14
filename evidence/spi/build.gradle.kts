plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-spi")
}

dependencies {
    api(project(":evidence:contract"))
    api(project(":workspace:contract"))
}
