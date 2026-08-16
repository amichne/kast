plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-recovery")
}

dependencies {
    api(project(":change:contract"))
    api(project(":evidence:contract"))
}
