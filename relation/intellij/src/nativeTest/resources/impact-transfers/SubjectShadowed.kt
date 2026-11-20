package fixture.impact.subject

@Suppress("NAME_SHADOWING")
fun shadowedSubject(selectedId: String, otherId: String, succeed: Boolean): String =
    when (val result = /*<binding-init>*/ produce(selectedId, otherId, succeed) /*</binding-init>*/) {
        is Outcome.Success -> {
            val result = Leaf(otherId)
            /*<excluded-read>*/ result.id /*</excluded-read>*/
        }
        Outcome.Error -> "no selected value"
    }
