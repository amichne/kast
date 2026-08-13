plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-spi")
}

dependencies {
    api(project(":workspace:contract"))
}
