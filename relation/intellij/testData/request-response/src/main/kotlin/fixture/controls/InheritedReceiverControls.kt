package fixture.controls

class InheritedPayload

interface InheritedScope {
    fun InheritedPayload.preserveInherited(): InheritedPayload = this
    fun InheritedPayload.selectDispatch(): InheritedScope = this@InheritedScope
    fun InheritedPayload.keepArgument(value: InheritedPayload): InheritedPayload = value
}

class FinalInheritedScope : InheritedScope

class ReplacingInheritedScope : InheritedScope {
    override fun InheritedPayload.preserveInherited(): InheritedPayload = InheritedPayload()
    override fun InheritedPayload.keepArgument(value: InheritedPayload): InheritedPayload = this
}

open class OpenInheritedScope : InheritedScope

fun FinalInheritedScope.inheritedExtension(input: InheritedPayload): InheritedPayload =
    /*<receiver>*/input/*</receiver>*/.preserveInherited()

fun FinalInheritedScope.inheritedDispatch(input: InheritedPayload): InheritedScope =
    /*<receiver>*/input/*</receiver>*/.selectDispatch()

fun ReplacingInheritedScope.overriddenExtension(input: InheritedPayload): InheritedPayload =
    /*<receiver>*/input/*</receiver>*/.preserveInherited()

fun InheritedScope.interfaceExtension(input: InheritedPayload): InheritedPayload =
    /*<receiver>*/input/*</receiver>*/.preserveInherited()

fun OpenInheritedScope.openExtension(input: InheritedPayload): InheritedPayload =
    /*<receiver>*/input/*</receiver>*/.preserveInherited()

fun FinalInheritedScope.inheritedArgument(receiver: InheritedPayload, value: InheritedPayload): InheritedPayload =
    receiver.keepArgument(/*<argument>*/value/*</argument>*/)

fun ReplacingInheritedScope.overriddenArgument(receiver: InheritedPayload, value: InheritedPayload): InheritedPayload =
    receiver.keepArgument(/*<argument>*/value/*</argument>*/)

interface InheritedMember {
    fun preserveMember(): InheritedMember = this
}

class FinalInheritedMember : InheritedMember

fun inheritedMember(input: FinalInheritedMember): InheritedMember =
    /*<receiver>*/input/*</receiver>*/.preserveMember()
