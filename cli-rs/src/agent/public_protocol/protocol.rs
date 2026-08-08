use super::domain::{
    IssuedSymbolSelector, ReferenceOccurrence, SelectableSymbol, SymbolIdentity, SymbolMatch,
    SymbolQuery,
};
use super::impact::{ImpactConfidence, ImpactNode};
pub(super) use super::registry::OperationId;
use super::traversal_types::RelationRecord;
use serde::Serialize;

pub(super) const PUBLIC_PROTOCOL_SCHEMA_VERSION: u32 = 2;

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
    result: EnvelopeResult,
}

impl ProtocolEnvelope {
    pub(super) fn complete(operation: OperationId, result: ProtocolResult) -> Self {
        Self::new(
            operation,
            OperationStatus::Complete,
            EnvelopeResult::Typed(result),
        )
    }

    pub(super) fn qualified(operation: OperationId, result: ProtocolResult) -> Self {
        Self::new(
            operation,
            OperationStatus::Qualified,
            EnvelopeResult::Typed(result),
        )
    }

    pub(super) fn rejected(operation: OperationId, failure: ProtocolFailure) -> Self {
        Self::new(
            operation,
            OperationStatus::Rejected,
            EnvelopeResult::Typed(ProtocolResult::Rejected { failure }),
        )
    }

    pub(crate) fn projected(
        operation: OperationId,
        status: OperationStatus,
        fields: serde_json::Map<String, serde_json::Value>,
    ) -> Self {
        let definition = operation.definition();
        let [result_type] = definition.result_discriminators else {
            return Self::rejected(
                operation,
                ProtocolFailure::BackendContractViolation {
                    message: "projected operation has more than one result discriminator"
                        .to_string(),
                },
            );
        };
        if fields.contains_key("type") {
            return Self::rejected(
                operation,
                ProtocolFailure::BackendContractViolation {
                    message: "projected result attempted to replace its registry discriminator"
                        .to_string(),
                },
            );
        }
        Self::new(
            operation,
            status,
            EnvelopeResult::Projected(ProjectedResult {
                result_type,
                fields,
            }),
        )
    }

    pub(crate) fn projected_rejected(
        operation: OperationId,
        failure: &impl Serialize,
    ) -> Result<Self, serde_json::Error> {
        let failure = serde_json::to_value(failure)?;
        if failure
            .get("type")
            .and_then(serde_json::Value::as_str)
            .is_none()
        {
            return Ok(Self::rejected(
                operation,
                ProtocolFailure::BackendContractViolation {
                    message: "projected rejection omitted its typed failure discriminator"
                        .to_string(),
                },
            ));
        }
        Ok(Self::new(
            operation,
            OperationStatus::Rejected,
            EnvelopeResult::ProjectedRejected(ProjectedRejectedResult {
                result_type: RejectedResultType::Rejected,
                failure,
            }),
        ))
    }

    pub(crate) fn backend_rejected(
        operation: OperationId,
        code: impl Into<String>,
        message: impl Into<String>,
    ) -> Self {
        Self::rejected(
            operation,
            ProtocolFailure::BackendRejected {
                code: code.into(),
                message: message.into(),
            },
        )
    }

    pub(crate) fn actionable_rejected(
        operation: OperationId,
        code: impl Into<String>,
        message: impl Into<String>,
        next: impl Into<String>,
    ) -> Self {
        Self::rejected(
            operation,
            ProtocolFailure::ActionableFailure {
                code: code.into(),
                message: message.into(),
                next: next.into(),
            },
        )
    }

    fn new(operation: OperationId, status: OperationStatus, result: EnvelopeResult) -> Self {
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
#[serde(untagged)]
enum EnvelopeResult {
    Typed(ProtocolResult),
    Projected(ProjectedResult),
    ProjectedRejected(ProjectedRejectedResult),
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ProjectedResult {
    #[serde(rename = "type")]
    result_type: &'static str,
    #[serde(flatten)]
    fields: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Serialize)]
struct ProjectedRejectedResult {
    #[serde(rename = "type")]
    result_type: RejectedResultType,
    failure: serde_json::Value,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "kebab-case")]
enum RejectedResultType {
    Rejected,
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
    Relations {
        selector: IssuedSymbolSelector,
        subject: SymbolIdentity,
        records: Vec<RelationRecord>,
        page: ProtocolPage,
        limitations: Vec<ProtocolLimitation>,
    },
    Impact {
        selector: IssuedSymbolSelector,
        subject: SymbolIdentity,
        nodes: Vec<ImpactNode>,
        page: ProtocolPage,
        confidence: ImpactConfidence,
        limitations: Vec<ProtocolLimitation>,
    },
    ImpactQualified {
        selector: IssuedSymbolSelector,
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
    ActionableFailure {
        code: String,
        message: String,
        next: String,
    },
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
    #[serde(rename(serialize = "tampered", deserialize = "TAMPERED"))]
    Tampered,
    #[serde(rename(serialize = "wrong-workspace", deserialize = "WRONG_WORKSPACE"))]
    WrongWorkspace,
    #[serde(rename(serialize = "wrong-backend", deserialize = "WRONG_BACKEND"))]
    WrongBackend,
    #[serde(rename(serialize = "stale", deserialize = "STALE"))]
    Stale,
    #[serde(rename(serialize = "family-not-allowed", deserialize = "FAMILY_NOT_ALLOWED"))]
    FamilyNotAllowed,
    #[serde(rename(serialize = "unavailable", deserialize = "UNAVAILABLE"))]
    Unavailable,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(super) enum SelectorRecovery {
    #[serde(rename(serialize = "resolve-again", deserialize = "RESOLVE_AGAIN"))]
    ResolveAgain,
    #[serde(rename(
        serialize = "resolve-in-current-workspace",
        deserialize = "RESOLVE_IN_CURRENT_WORKSPACE"
    ))]
    ResolveInCurrentWorkspace,
    #[serde(rename(
        serialize = "resolve-with-active-backend",
        deserialize = "RESOLVE_WITH_ACTIVE_BACKEND"
    ))]
    ResolveWithActiveBackend,
    #[serde(rename(
        serialize = "choose-compatible-operation",
        deserialize = "CHOOSE_COMPATIBLE_OPERATION"
    ))]
    ChooseCompatibleOperation,
    #[serde(rename(
        serialize = "use-explicit-selector",
        deserialize = "USE_EXPLICIT_SELECTOR"
    ))]
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
    SourceImageUnproven,
    ContinuationExpired,
    ContinuationInvalid,
}
