package io.github.amichne.kast.relation.contract

/** Existing model factories keep each shared binding, frame, stack, and selection condition coherent. */
internal fun ValueModelApplication.shareCallableEvidence(
    source: ValueSite,
    binding: ReviewedLibraryBinding,
    shareFrame: (ValueCallFrame) -> ValueCallFrame,
): ValueModelApplication =
    when (this) {
        is ValueModelApplication.CollectionInsertion -> sharedInsertion(source, binding)
        is ValueModelApplication.CollectionSelection ->
            if (alreadyShares(source, binding)) this
            else ValueModelApplication.CollectionSelection.selectFirst(source, binding).sharedProof()
        is ValueModelApplication.ScopeCallbackInput -> {
            val sharedFrame = shareFrame(frame)
            if (source === this.source && sharedFrame === frame) this
            else ValueModelApplication.ScopeCallbackInput.enter(source, sharedFrame).sharedProof()
        }
        is ValueModelApplication.ScopeCallbackReturn -> {
            val sharedFrame = shareFrame(frame)
            if (source === this.source && sharedFrame === frame) this
            else ValueModelApplication.ScopeCallbackReturn.leave(source, sharedFrame).sharedProof()
        }
    }

private fun ValueModelApplication.CollectionInsertion.sharedInsertion(
    source: ValueSite,
    binding: ReviewedLibraryBinding,
): ValueModelApplication.CollectionInsertion =
    if (source === this.source && binding === this.binding) this
    else ValueModelApplication.CollectionInsertion.insertSingle(source, binding).sharedProof()

private fun ValueModelApplication.CollectionSelection.alreadyShares(
    source: ValueSite,
    binding: ReviewedLibraryBinding,
): Boolean = source === this.source && binding === this.binding
