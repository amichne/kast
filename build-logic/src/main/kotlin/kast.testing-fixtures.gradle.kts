plugins {
    id("kast.kotlin-library")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(catalog.findLibrary("coroutines-core").get())
    api(catalog.findLibrary("serialization-json").get())
}
