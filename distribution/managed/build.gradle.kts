plugins {
    id("kast.kotlin-serialization")
    id("kast.role.filesystem-write")
}

group = "${providers.gradleProperty("GROUP").get()}.distribution"

base {
    archivesName.set("distribution-managed")
}

dependencies {
    implementation(project(":distribution:contract"))
}
