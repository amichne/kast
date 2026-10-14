package consumer

fun atLeastOnceAnonymousFunction() {
    effects.atLeastOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}
