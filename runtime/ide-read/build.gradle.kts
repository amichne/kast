plugins {
    id("kast.kotlin-library")
    id("kast.role.ide-read-only")
}

group = "${rootProject.group}.runtime"

base {
    archivesName.set("runtime-ide-read")
}

dependencies {
    implementation(project(":workspace:contract"))
}
