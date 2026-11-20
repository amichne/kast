package fixture.impact.capture

data class Conversion(val trackingId: String?)

data class Tracking(val componentId: String, val conversion: Conversion?, val reportingName: String?)

class Receiver

fun source(input: Tracking): Tracking = input

fun observe(value: String): String = value

fun direct(input: Tracking): String {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return observe(/*<read>*/ captured.componentId /*</read>*/)
}

fun outerApply(input: Tracking): Receiver {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return /*<receiver-result>*/ Receiver().apply {
        observe(/*<read>*/ captured.componentId /*</read>*/)
    } /*</receiver-result>*/
}

fun letOnly(input: Tracking) {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    /*<let-receiver>*/ captured.conversion?.trackingId /*</let-receiver>*/?.let { id ->
        observe(/*<let-read>*/ id /*</let-read>*/)
    }
}

fun nested(input: Tracking): Receiver {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return /*<receiver-result>*/ Receiver().apply {
        observe(/*<component-read>*/ captured.componentId /*</component-read>*/)
        /*<let-receiver>*/ captured.conversion?.trackingId /*</let-receiver>*/?.let { id ->
            observe(/*<let-read>*/ id /*</let-read>*/)
        }
        captured.reportingName?.let { name -> observe(/*<reporting-read>*/ name /*</reporting-read>*/) }
    } /*</receiver-result>*/
}
