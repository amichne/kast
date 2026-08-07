use super::domain::{
    IssuedSymbolSelector, ReferenceOccurrence, SelectableSymbol, SymbolIdentity, SymbolMatch,
    SymbolQuery,
};
use serde::Serialize;

pub(super) const PUBLIC_PROTOCOL_SCHEMA_VERSION: u32 = 2;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub(crate) enum OperationId {
    #[serde(rename = "symbol.search")]
    SymbolSearch,
    #[serde(rename = "symbol.resolve")]
    SymbolResolve,
    #[serde(rename = "symbol.show")]
    SymbolShow,
    #[serde(rename = "relation.references")]
    RelationReferences,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum OperationStatus {
    Complete,
    Qualified,
    Rejected,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProtocolEnvelope {
    schema_version: u32,
    operation: OperationId,
    status: OperationStatus,
    result: ProtocolResult,
}

impl ProtocolEnvelope {
    pub(super) fn complete(operation: OperationId, result: ProtocolResult) -> Self {
        Self::new(operation, OperationStatus::Complete, result)
    }

    pub(super) fn qualified(operation: OperationId, result: ProtocolResult) -> Self {
        Self::new(operation, OperationStatus::Qualified, result)
    }

    pub(super) fn rejected(operation: OperationId, failure: ProtocolFailure) -> Self {
        Self::new(
            operation,
            OperationStatus::Rejected,
            ProtocolResult::Rejected { failure },
        )
    }

    fn new(operation: OperationId, status: OperationStatus, result: ProtocolResult) -> Self {
        Self {
            schema_version: PUBLIC_PROTOCOL_SCHEMA_VERSION,
            operation,
            status,
            result,
        }
    }

    pub(crate) fn exit_code(&self) -> i32 {
        i32::from(self.status == OperationStatus::Rejected)
    }
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "type",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase"
)]
pub(super) enum ProtocolResult {
    Matches {
        matches: Vec<SymbolMatch>,
    },
    Resolved {
        symbol: super::domain::SymbolRecord,
        selector: IssuedSymbolSelector,
    },
    NotFound {
        query: SymbolQuery,
    },
    Ambiguous {
        query: SymbolQuery,
        candidates: Vec<SelectableSymbol>,
    },
    Symbol {
        selector: IssuedSymbolSelector,
        symbol: SymbolIdentity,
    },
    References {
        selector: IssuedSymbolSelector,
        subject: SymbolIdentity,
        references: Vec<ReferenceOccurrence>,
        page: ProtocolPage,
        limitations: Vec<ProtocolLimitation>,
    },
    Rejected {
        failure: ProtocolFailure,
    },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase"
)]
pub(super) enum ProtocolFailure {
    InvalidInput {
        field: &'static str,
        reason: String,
    },
    SelectorRejected {
        reason: SelectorRejectionReason,
        recovery: SelectorRecovery,
    },
    BackendRejected {
        code: String,
        message: String,
    },
    BackendContractViolation {
        message: String,
    },
    SubjectNotFound,
    SubjectIdentityMismatch,
    UnsupportedSubjectKind,
    ContinuationInvalid,
    ContinuationMismatch,
    ContinuationStale,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(super) enum SelectorRejectionReason {
    #[serde(alias = "TAMPERED")]
    Tampered,
    #[serde(alias = "WRONG_WORKSPACE")]
    WrongWorkspace,
    #[serde(alias = "WRONG_BACKEND")]
    WrongBackend,
    #[serde(alias = "STALE")]
    Stale,
    #[serde(alias = "FAMILY_NOT_ALLOWED")]
    FamilyNotAllowed,
    #[serde(alias = "UNAVAILABLE")]
    Unavailable,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(super) enum SelectorRecovery {
    #[serde(alias = "RESOLVE_AGAIN")]
    ResolveAgain,
    #[serde(alias = "RESOLVE_IN_CURRENT_WORKSPACE")]
    ResolveInCurrentWorkspace,
    #[serde(alias = "RESOLVE_WITH_ACTIVE_BACKEND")]
    ResolveWithActiveBackend,
    #[serde(alias = "CHOOSE_COMPATIBLE_OPERATION")]
    ChooseCompatibleOperation,
    #[serde(alias = "USE_EXPLICIT_SELECTOR")]
    UseExplicitSelector,
}

impl SelectorRejectionReason {
    pub(super) fn recovery(self) -> SelectorRecovery {
        match self {
            Self::Tampered | Self::Stale => SelectorRecovery::ResolveAgain,
            Self::WrongWorkspace => SelectorRecovery::ResolveInCurrentWorkspace,
            Self::WrongBackend => SelectorRecovery::ResolveWithActiveBackend,
            Self::FamilyNotAllowed => SelectorRecovery::ChooseCompatibleOperation,
            Self::Unavailable => SelectorRecovery::UseExplicitSelector,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ProtocolPage {
    pub cardinality: ProtocolCardinality,
    pub returned: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continuation: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase"
)]
pub(super) enum ProtocolCardinality {
    Exact { count: u64 },
    KnownMinimum { count: u64 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
#[serde(rename_all(serialize = "kebab-case", deserialize = "SCREAMING_SNAKE_CASE"))]
pub(super) enum ProtocolLimitation {
    IdentityUnproven,
    ProjectScopeIncomplete,
    SourceSetScopeIncomplete,
    SourceSetExcluded,
    IndexNotReady,
    IndexStale,
    BackendIncomplete,
    BackendUnavailable,
    FamilySearchInProgress,
    FamilySearchIncomplete,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    TimedOut,
    Cancelled,
    GenerationChanged,
    ContinuationExpired,
    ContinuationInvalid,
}
