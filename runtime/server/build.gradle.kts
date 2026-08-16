plugins {
    id("kast.kotlin-serialization")
    id("kast.role.transport")
}

dependencies {
    api(project(":protocol:contract"))
    api(project(":protocol:wire"))
}
