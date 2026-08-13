plugins {
    id("kast.kotlin-library")
    id("kast.role.spi")
}

dependencies {
    api(project(":workspace:contract"))
}
