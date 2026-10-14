package semanticowners
fun target() {}

fun supply() {
    kotlin.lazy { target() }
}
