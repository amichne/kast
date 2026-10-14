package semanticowners
class Target

fun entry() {
    (fun() {
        kotlin.lazy { Target() }
    })()
}
