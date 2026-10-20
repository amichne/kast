package io.github.amichne.kast.relation.contract

/** A selected subject and a resolved endpoint can carry the same full current proof. */
fun RelationEndpoint.hasSameProofAs(other: RelationEndpoint): Boolean =
    lease == other.lease &&
        scope == other.scope &&
        constraints == other.constraints &&
        file == other.file &&
        range == other.range &&
        name == other.name &&
        qualifiedIdentity == other.qualifiedIdentity &&
        kind == other.kind &&
        signature == other.signature &&
        compilerIdentity == other.compilerIdentity
