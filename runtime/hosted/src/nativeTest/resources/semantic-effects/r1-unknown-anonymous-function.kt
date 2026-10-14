package consumer

fun unknownAnonymousFunction() {
    effects.unknown(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}
