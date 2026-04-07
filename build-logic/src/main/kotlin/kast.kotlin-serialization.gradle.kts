plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
}


private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(catalog.findLibrary("serialization.json").get())
}
