package semanticowners
fun target() {}

fun entry() {
    fun supply() {
        kotlin.lazy { target() }
    }
    supply()
}
