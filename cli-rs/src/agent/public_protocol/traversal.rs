use super::backend::{
    RelationshipEvidence, SymbolIdentityInput, page, request, selector_rejection,
};
use super::domain::{ExactSymbolRequest, IssuedSymbolSelector, SymbolSelector};
use super::execution::authenticate_selector;
use super::protocol::{OperationId, ProtocolEnvelope, ProtocolFailure, ProtocolResult};
use super::traversal_types::{
    RelationRecord, RelationRecordInput, decode_continuation, issue_continuation,
};
use serde::Deserialize;
use serde_json::{Value, json};

const MAX_RESULTS: u16 = 200;

#[derive(Clone, Copy, Debug)]
pub(super) enum TraversalOperation {
    CallsIncoming,
    CallsOutgoing,
    Implementations,
    HierarchySupertypes,
    HierarchySubtypes,
}

impl TraversalOperation {
    fn operation_id(self) -> OperationId {
        match self {
            Self::CallsIncoming => OperationId::RelationCallsIncoming,
            Self::CallsOutgoing => OperationId::RelationCallsOutgoing,
            Self::Implementations => OperationId::RelationImplementations,
            Self::HierarchySupertypes => OperationId::RelationHierarchySupertypes,
            Self::HierarchySubtypes => OperationId::RelationHierarchySubtypes,
        }
    }

    fn family(self) -> &'static str {
        match self {
            Self::CallsIncoming => "CALLERS",
            Self::CallsOutgoing => "CALLEES",
            Self::Implementations => "IMPLEMENTATIONS",
            Self::HierarchySupertypes | Self::HierarchySubtypes => "HIERARCHY",
        }
    }

    pub(super) fn tag(self) -> &'static str {
        match self {
            Self::CallsIncoming => "calls-incoming",
            Self::CallsOutgoing => "calls-outgoing",
            Self::Implementations => "implementations",
            Self::HierarchySupertypes => "hierarchy-supertypes",
            Self::HierarchySubtypes => "hierarchy-subtypes",
        }
    }

    fn method(self) -> &'static str {
        match self {
            Self::CallsIncoming | Self::CallsOutgoing => "symbol/callers",
            Self::Implementations => "symbol/implementations",
            Self::HierarchySupertypes | Self::HierarchySubtypes => "symbol/hierarchy",
        }
    }

    fn params(self, selector: &IssuedSymbolSelector, continuation: Option<String>) -> Value {
        let mut params = json!({
            "selectorHandle": selector.as_str(),
            "maxResults": MAX_RESULTS,
        });
        match self {
            Self::CallsIncoming => {
                params["direction"] = json!("incoming");
                params["depth"] = json!(1);
            }
            Self::CallsOutgoing => {
                params["direction"] = json!("outgoing");
                params["depth"] = json!(1);
            }
            Self::HierarchySupertypes => {
                params["direction"] = json!("SUPERTYPES");
                params["depth"] = json!(8);
            }
            Self::HierarchySubtypes => {
                params["direction"] = json!("SUBTYPES");
                params["depth"] = json!(8);
            }
            Self::Implementations => {}
        }
        if let Some(continuation) = continuation {
            params["pageToken"] = Value::String(continuation);
        }
        params
    }

    fn accepts(self, record: &RelationRecord) -> bool {
        matches!(
            (self, record),
            (Self::CallsIncoming, RelationRecord::IncomingCall { .. })
                | (Self::CallsOutgoing, RelationRecord::OutgoingCall { .. })
                | (Self::Implementations, RelationRecord::Implementation { .. })
                | (Self::HierarchySupertypes, RelationRecord::Supertype { .. })
                | (Self::HierarchySubtypes, RelationRecord::Subtype { .. })
        )
    }
}

pub(super) fn execute(
    runtime: &crate::cli::AgentRuntimeArgs,
    input: ExactSymbolRequest,
    operation: TraversalOperation,
) -> ProtocolEnvelope {
    let operation_id = operation.operation_id();
    let selector = match authenticate_selector(runtime, input.selector, operation.family()) {
        Ok(selector) => selector,
        Err(failure) => return ProtocolEnvelope::rejected(operation_id, failure),
    };
    let continuation = match input
        .continuation
        .map(|value| decode_continuation(runtime, &selector, operation, &value))
        .transpose()
    {
        Ok(continuation) => continuation,
        Err(failure) => return ProtocolEnvelope::rejected(operation_id, failure),
    };
    let response = request(
        runtime,
        operation.method(),
        operation.params(selector.issued(), continuation),
    )
    .and_then(parse_response);
    let response = match response {
        Ok(response) => response,
        Err(failure) => return ProtocolEnvelope::rejected(operation_id, failure),
    };
    match normalize_response(runtime, selector, operation, response) {
        Ok((result, true)) => ProtocolEnvelope::complete(operation_id, result),
        Ok((result, false)) => ProtocolEnvelope::qualified(operation_id, result),
        Err(failure) => ProtocolEnvelope::rejected(operation_id, failure),
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum TraversalResponse {
    #[serde(rename = "AVAILABLE")]
    Available {
        subject: SymbolIdentityInput,
        records: Vec<RelationRecordInput>,
        page: TraversalPageInput,
    },
    #[serde(rename = "DEGRADED")]
    Degraded {
        subject: SymbolIdentityInput,
        reason: DegradedReason,
        evidence: RelationshipEvidence,
        #[serde(default)]
        records: Vec<RelationRecordInput>,
    },
    #[serde(rename = "SUBJECT_NOT_FOUND")]
    SubjectNotFound { selector: Value },
    #[serde(rename = "SUBJECT_IDENTITY_MISMATCH")]
    SubjectIdentityMismatch { selector: Value, actual: Value },
    #[serde(rename = "UNSUPPORTED_SUBJECT_KIND")]
    UnsupportedSubjectKind { selector: Value, subject: Value },
    #[serde(rename = "CURSOR_STALE")]
    CursorStale { reason: CursorStaleReason },
    #[serde(rename = "CURSOR_INVALID")]
    CursorInvalid { reason: CursorInvalidReason },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorRejected {
        reason: super::protocol::SelectorRejectionReason,
        recovery: super::protocol::SelectorRecovery,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TraversalPageInput {
    evidence: RelationshipEvidence,
    returned_count: usize,
    visited_candidate_count: usize,
    truncated: bool,
    #[serde(default)]
    next_handle: Option<String>,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum DegradedReason {
    CallHierarchyUnavailable,
    ImplementationsUnavailable,
    TypeHierarchyUnavailable,
    CandidateBudgetReached,
    TraversalStateBudgetReached,
    Timeout,
    Cancelled,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CursorStaleReason {
    GenerationChanged,
    Expired,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CursorInvalidReason {
    UnknownHandle,
    FamilyMismatch,
    QueryMismatch,
}

fn normalize_response(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: SymbolSelector,
    operation: TraversalOperation,
    response: TraversalResponse,
) -> Result<(ProtocolResult, bool), ProtocolFailure> {
    let (subject, records, evidence, next_handle, complete) = match response {
        TraversalResponse::Available {
            subject,
            records,
            page,
        } => {
            if page.returned_count != records.len()
                || page.visited_candidate_count < records.len()
                || page.truncated != page.next_handle.is_some()
            {
                return Err(contract_violation(
                    "relationship page evidence was inconsistent",
                ));
            }
            (subject, records, page.evidence, page.next_handle, true)
        }
        TraversalResponse::Degraded {
            subject,
            reason,
            evidence,
            records,
        } => {
            validate_degraded_reason(operation, reason)?;
            (subject, records, evidence, None, false)
        }
        TraversalResponse::SubjectNotFound { selector } => {
            drop(selector);
            return Err(ProtocolFailure::SubjectNotFound);
        }
        TraversalResponse::SubjectIdentityMismatch { selector, actual } => {
            drop((selector, actual));
            return Err(ProtocolFailure::SubjectIdentityMismatch);
        }
        TraversalResponse::UnsupportedSubjectKind { selector, subject } => {
            drop((selector, subject));
            return Err(ProtocolFailure::UnsupportedSubjectKind);
        }
        TraversalResponse::CursorStale { reason } => {
            let _ = reason;
            return Err(ProtocolFailure::ContinuationStale);
        }
        TraversalResponse::CursorInvalid { reason } => {
            return Err(match reason {
                CursorInvalidReason::FamilyMismatch | CursorInvalidReason::QueryMismatch => {
                    ProtocolFailure::ContinuationMismatch
                }
                CursorInvalidReason::UnknownHandle => ProtocolFailure::ContinuationInvalid,
            });
        }
        TraversalResponse::SelectorRejected { reason, recovery } => {
            return Err(selector_rejection(reason, recovery));
        }
    };
    let subject = subject.normalize(runtime)?;
    if &subject != selector.identity() {
        return Err(ProtocolFailure::SubjectIdentityMismatch);
    }
    let records = records
        .into_iter()
        .map(|record| record.normalize(runtime))
        .collect::<Result<Vec<_>, _>>()?;
    if records.iter().any(|record| !operation.accepts(record)) {
        return Err(contract_violation(
            "relationship record used the wrong semantic kind",
        ));
    }
    let (cardinality, limitations, evidence_complete) = evidence.into_protocol();
    let known_minimum = match cardinality {
        super::protocol::ProtocolCardinality::Exact { count }
        | super::protocol::ProtocolCardinality::KnownMinimum { count } => count,
    };
    if known_minimum < records.len() as u64
        || next_handle.is_some() && known_minimum <= records.len() as u64
    {
        return Err(contract_violation(
            "relationship cardinality understated its page",
        ));
    }
    let continuation = next_handle
        .map(|raw| issue_continuation(runtime, &selector, operation, &raw))
        .transpose()?;
    let page = page(cardinality, records.len(), continuation);
    Ok((
        ProtocolResult::Relations {
            selector: selector.issued().clone(),
            subject,
            records,
            page,
            limitations,
        },
        complete && evidence_complete,
    ))
}

fn validate_degraded_reason(
    operation: TraversalOperation,
    reason: DegradedReason,
) -> Result<(), ProtocolFailure> {
    let family_reason = matches!(
        (operation, reason),
        (
            TraversalOperation::CallsIncoming | TraversalOperation::CallsOutgoing,
            DegradedReason::CallHierarchyUnavailable
        ) | (
            TraversalOperation::Implementations,
            DegradedReason::ImplementationsUnavailable
        ) | (
            TraversalOperation::HierarchySupertypes | TraversalOperation::HierarchySubtypes,
            DegradedReason::TypeHierarchyUnavailable
        )
    );
    let shared_reason = matches!(
        reason,
        DegradedReason::CandidateBudgetReached
            | DegradedReason::TraversalStateBudgetReached
            | DegradedReason::Timeout
            | DegradedReason::Cancelled
    );
    (family_reason || shared_reason)
        .then_some(())
        .ok_or_else(|| contract_violation("relationship limitation belonged to another family"))
}

fn parse_response(value: Value) -> Result<TraversalResponse, ProtocolFailure> {
    serde_json::from_value(value).map_err(|error| {
        contract_violation(&format!(
            "relationship response violated its contract: {error}"
        ))
    })
}

fn contract_violation(message: &str) -> ProtocolFailure {
    ProtocolFailure::BackendContractViolation {
        message: message.to_string(),
    }
}
