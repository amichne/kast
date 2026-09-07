plugins { `kotlin-dsl` }
repositories { gradlePluginPortal(); mavenCentral() }
kotlin.sourceSets.named("main") { kotlin.srcDir("../program") }
