package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Restores the reverse supplier closure through current-authority owning factories. */
internal class CallbackSupplierReadmission(private val endpoints: CallbackEndpointReadmissions) {
    private val evidence = CallbackEvidenceReadmission(endpoints)

    fun inventory(previous: CompleteCallbackSupplierInventory): CallbackReadmission<CompleteCallbackSupplierInventory> =
        evidence.formal(previous.root).then { root ->
            readmitEach(previous.partitions, ::partition).then { partitions ->
                CompleteCallbackSupplierInventory.fromCompiler(root, previous.domain, partitions).supplier()
            }
        }

    private fun partition(
        previous: CompleteCallbackSupplierPartition
    ): CallbackReadmission<CompleteCallbackSupplierPartition> =
        evidence.formal(previous.formal).then { formal ->
            readmitEach(previous.suppliers, ::supplier).then { suppliers ->
                readmitEach(previous.incoming, evidence::edge).then { incoming ->
                    CompleteCallbackSupplierPartition.fromCompiler(
                            formal,
                            suppliers,
                            incoming,
                            CallbackInvocationScan.EXHAUSTIVE,
                        )
                        .supplier()
                }
            }
        }

    private fun supplier(previous: CallbackParameterSupplier): CallbackReadmission<CallbackParameterSupplier> =
        evidence.argument(previous.binding).then { binding ->
            selection(previous.selection, binding).then { selected ->
                value(previous.value).then { value ->
                    CallbackParameterSupplier.fromCompiler(binding, selected, value).supplier()
                }
            }
        }

    private fun selection(
        previous: CallbackSupplierSelection,
        binding: CallbackArgumentBinding,
    ): CallbackReadmission<CallbackSupplierSelection> =
        when (previous) {
            is CallbackSupplierSelection.Explicit ->
                evidence.site(previous.argument).then { Refinement.Refined(CallbackSupplierSelection.Explicit(it)) }
            is CallbackSupplierSelection.Default ->
                evidence.formal(previous.declaration.parameter).then { formal ->
                    when (
                        val admitted = CallbackDefaultBinding.fromCompiler(formal, previous.declaration.defaultValue)
                    ) {
                        is Refinement.Refined ->
                            Refinement.Refined(
                                CallbackSupplierSelection.Default(
                                    CallbackArgumentOmission.fromCompiler(binding),
                                    admitted.value,
                                )
                            )
                        is Refinement.Rejected ->
                            Refinement.Rejected(CallbackSummaryReadmissionFailure.Callback(admitted.failure))
                    }
                }
        }

    private fun value(previous: ImmutableCallbackValue): CallbackReadmission<ImmutableCallbackValue> =
        origin(previous.origin).then { origin ->
            evidence.site(previous.source).then { source ->
                evidence.site(previous.destination).then { destination ->
                    readmitEach(previous.transfers, evidence::transfer).then { transfers ->
                        when (
                            val admitted = ImmutableCallbackValue.fromCompiler(origin, source, destination, transfers)
                        ) {
                            is Refinement.Refined -> admitted
                            is Refinement.Rejected ->
                                Refinement.Rejected(CallbackSummaryReadmissionFailure.ImmutableValue(admitted.failure))
                        }
                    }
                }
            }
        }

    private fun origin(previous: ImmutableCallbackValueOrigin): CallbackReadmission<ImmutableCallbackValueOrigin> =
        when (previous) {
            is ImmutableCallbackValueOrigin.Anonymous -> Refinement.Refined(previous)
            is ImmutableCallbackValueOrigin.Named ->
                endpoints.endpoint(previous.target).then { target ->
                    receiver(previous.receivers.dispatch).then { dispatch ->
                        receiver(previous.receivers.extension).then { extension ->
                            Refinement.Refined(
                                ImmutableCallbackValueOrigin.Named(
                                    previous.occurrence,
                                    target,
                                    CallbackReferenceReceivers(dispatch, extension),
                                )
                            )
                        }
                    }
                }
            is ImmutableCallbackValueOrigin.Returned ->
                factory(previous.factory).then { Refinement.Refined(ImmutableCallbackValueOrigin.Returned(it)) }
        }

    private fun receiver(previous: CallbackReferenceReceiver): CallbackReadmission<CallbackReferenceReceiver> =
        when (previous) {
            is CallbackReferenceReceiver.Implicit ->
                endpoints.declaration(previous.declaration).then {
                    Refinement.Refined(CallbackReferenceReceiver.Implicit(it))
                }
            is CallbackReferenceReceiver.Bound,
            CallbackReferenceReceiver.Absent,
            CallbackReferenceReceiver.Unbound -> Refinement.Refined(previous)
        }

    private fun factory(previous: CallbackFactoryReturn): CallbackReadmission<CallbackFactoryReturn> =
        evidence.call(previous.invocation).then { invocation ->
            value(previous.returnedValue).then { returned ->
                readmitEach(previous.captures, ::capture).then { captures ->
                    CallbackFactoryBodyReadmission(endpoints).read(previous.bodyCalls).then { bodyCalls ->
                        CallbackFactoryReturn.fromCompiler(invocation, returned, captures, bodyCalls).factoryFailure()
                    }
                }
            }
        }

    private fun capture(previous: CallbackFactoryCapture): CallbackReadmission<CallbackFactoryCapture> =
        evidence.argument(previous.binding).then { binding ->
            captureSelection(previous.selection).then { selection ->
                captureContent(previous.content).then { content ->
                    CallbackFactoryCapture.fromCompiler(binding, selection, content).factoryFailure()
                }
            }
        }

    private fun captureSelection(
        previous: CallbackFactoryCaptureSelection
    ): CallbackReadmission<CallbackFactoryCaptureSelection> =
        when (previous) {
            is CallbackFactoryCaptureSelection.Explicit ->
                evidence.site(previous.value).then { Refinement.Refined(CallbackFactoryCaptureSelection.Explicit(it)) }
            is CallbackFactoryCaptureSelection.Default ->
                evidence.formal(previous.declaration.parameter).then { formal ->
                    when (
                        val admitted = CallbackDefaultBinding.fromCompiler(formal, previous.declaration.defaultValue)
                    ) {
                        is Refinement.Refined ->
                            Refinement.Refined(CallbackFactoryCaptureSelection.Default(admitted.value))
                        is Refinement.Rejected ->
                            Refinement.Rejected(CallbackSummaryReadmissionFailure.Callback(admitted.failure))
                    }
                }
        }

    private fun captureContent(
        previous: CallbackFactoryCaptureContent
    ): CallbackReadmission<CallbackFactoryCaptureContent> =
        when (previous) {
            CallbackFactoryCaptureContent.Scalar -> Refinement.Refined(previous)
            is CallbackFactoryCaptureContent.Callable ->
                readmitEach(previous.values, ::value).then { values ->
                    readmitEach(previous.invocations, evidence::invocation).then { invocations ->
                        Refinement.Refined(CallbackFactoryCaptureContent.Callable(values, invocations))
                    }
                }
        }
}

private fun <T> Refinement<T, CallbackFactoryReturnFailure>.factoryFailure(): CallbackReadmission<T> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Factory(failure))
    }

private fun <T> Refinement<T, CallbackSupplierFailure>.supplier(): CallbackReadmission<T> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Supplier(failure))
    }
