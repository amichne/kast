package consumer

fun atMostOnceAnonymousFunction() {
    effects.atMostOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}
