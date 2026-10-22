package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification

internal fun permanentDiscoveryLimitations(qualifications: Set<SymbolDiscoveryQualification>): Set<QueryLimitation> =
    setOf(QueryLimitation.DISCOVERY_INCOMPLETE) +
        qualifications.mapNotNull { qualification ->
            when (qualification) {
                SymbolDiscoveryQualification.BYTE_LIMIT_REACHED -> QueryLimitation.BYTE_LIMIT_REACHED
                SymbolDiscoveryQualification.WORK_LIMIT_REACHED -> QueryLimitation.WORK_LIMIT_REACHED
                SymbolDiscoveryQualification.TIME_LIMIT_REACHED -> QueryLimitation.TIME_LIMIT_REACHED
                SymbolDiscoveryQualification.RESULT_LIMIT_REACHED,
                SymbolDiscoveryQualification.DUMB_MODE_TRANSITION,
                SymbolDiscoveryQualification.PROVIDER_FAILURE,
                SymbolDiscoveryQualification.UNSCOPED_PROVIDER,
                SymbolDiscoveryQualification.UNSUPPORTED_ITEM,
                SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE -> null
            }
        }

internal fun permanentRelationLimitations(limitations: Set<RelationLimitation>): Set<QueryLimitation> =
    setOf(QueryLimitation.RELATION_INCOMPLETE) +
        limitations.mapNotNull { limitation ->
            when (limitation) {
                RelationLimitation.RESULT_LIMIT_REACHED -> QueryLimitation.RESULT_LIMIT_REACHED
                RelationLimitation.BYTE_LIMIT_REACHED -> QueryLimitation.BYTE_LIMIT_REACHED
                RelationLimitation.WORK_LIMIT_REACHED -> QueryLimitation.WORK_LIMIT_REACHED
                RelationLimitation.TIME_LIMIT_REACHED -> QueryLimitation.TIME_LIMIT_REACHED
                RelationLimitation.CANDIDATE_LIMIT_REACHED -> QueryLimitation.WORK_LIMIT_REACHED
                RelationLimitation.RETENTION_LIMIT_REACHED,
                RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE,
                RelationLimitation.DUMB_MODE_TRANSITION,
                RelationLimitation.UNRESOLVED_TARGET,
                RelationLimitation.UNSUPPORTED_ITEM,
                RelationLimitation.PROVIDER_FAILURE,
                RelationLimitation.PROVIDER_INCOMPLETE,
                RelationLimitation.PROVIDER_STALLED -> null
            }
        }
