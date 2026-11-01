package fixture.controls

fun reviewedSelectorKey(): String = "fixed"

fun selectorResultCannotBecomeSortedElement(): List<String> =
    /*<destination>*/listOf("left", "right").sortedBy {
        /*<selector-result>*/reviewedSelectorKey()/*</selector-result>*/
    }/*</destination>*/
