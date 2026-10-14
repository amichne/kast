package semanticowners
fun target() {}

fun entry() {
    val supply = kotlin.lazy { target() }
}
