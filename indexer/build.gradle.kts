plugins {
    id("kast.kotlin-library")
    id("kast.role.indexer-host")
}

dependencies {
    implementation(project(":runtime:composition"))
}
