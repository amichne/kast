package semanticowners
class Target

fun entry() {
    fun supply() {
        kotlin.lazy { Target() }
    }
    supply()
}
