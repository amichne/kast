package semanticowners
fun target() {}

fun entry() {
    (fun() {
        kotlin.lazy { target() }
    })()
}
