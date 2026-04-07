plugins {
    id("kast.standalone-app")
    kotlin("plugin.serialization")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(catalog.findLibrary("serialization.json").get())
}
