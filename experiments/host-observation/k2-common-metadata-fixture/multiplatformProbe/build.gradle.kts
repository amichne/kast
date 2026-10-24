plugins { kotlin("multiplatform") }
kotlin {
    jvm()
    js { nodejs() }
    jvmToolchain(25)
}
