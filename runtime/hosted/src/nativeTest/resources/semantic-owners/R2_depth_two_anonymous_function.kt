package semanticowners
fun target() {}

fun entry() {
    (fun() {
        (fun() {
            kotlin.lazy { target() }
        })()
    })()
}
