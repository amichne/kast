plugins {
    id("kast.testing-fixtures")
}

dependencies {
    api(project(":analysis-api"))

    // In-memory filesystem for testing (not in production runtime)
    api("com.google.jimfs:jimfs:1.3.0")
}
