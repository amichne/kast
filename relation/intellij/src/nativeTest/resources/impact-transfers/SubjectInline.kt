package fixture.impact.subject

fun inlineSubject(selectedId: String, otherId: String, succeed: Boolean): Readout =
    when (val result = /*<binding-init>*/ produce(selectedId, otherId, succeed) /*</binding-init>*/) {
        is Outcome.Success -> {
            val selected = /*<selected-read>*/ result.value.left.id /*</selected-read>*/
            val other = /*<sibling-read>*/ result.value.right.id /*</sibling-read>*/
            Readout(selected, other)
        }
        Outcome.Error -> Readout(/*<error-read>*/ "no selected value" /*</error-read>*/, "no other value")
    }
