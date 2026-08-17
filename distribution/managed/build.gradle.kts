plugins {
    id("kast.kotlin-library")
    id("kast.role.filesystem-write")
}

group = "${rootProject.group}.distribution"

base {
    archivesName.set("distribution-managed")
}

dependencies {
    implementation(project(":distribution:contract"))
}
