use super::domain::{
    IssuedSymbolSelector, RelationshipSelectorInput, SourceLocation, SymbolIdentity, SymbolKind,
    SymbolRecord, WorkspaceKotlinPath,
};
use super::protocol::{
    ProtocolCardinality, ProtocolFailure, ProtocolLimitation, ProtocolPage, SelectorRecovery,
    SelectorRejectionReason,
};
use crate::cli::AgentRuntimeArgs;
use serde::Deserialize;
use serde_json::{Value, json};

use super::traversal_types::ReferenceInput;

pub(super) fn request(
    runtime: &AgentRuntimeArgs,
    method: &str,
    params: Value,
) -> Result<Value, ProtocolFailure> {
    let request = super::super::json_rpc_request(method, params);
    let envelope = super::super::execute_request(super::super::AgentRequest {
        method: method.to_string(),
        request,
        runtime: runtime.clone(),
        full_response: true,
        operation: super::super::AgentOperation::ReadOnly,
    });
    if !envelope.ok {
        let error = envelope.error.unwrap_or_else(|| super::super::AgentError {
            code: "BACKEND_REQUEST_REJECTED".to_string(),
            message: "The backend rejected the request without a typed error.".to_string(),
            details: Default::default(),
        });
        return Err(ProtocolFailure::BackendRejected {
            code: error.code,
            message: error.message,
        });
    }
    envelope
        .result
        .ok_or_else(|| ProtocolFailure::BackendContractViolation {
            message: format!("{method} returned no result"),
        })
}

pub(super) fn normalize_path(
    runtime: &AgentRuntimeArgs,
    path: String,
) -> Result<WorkspaceKotlinPath, ProtocolFailure> {
    let normalizer =
        super::super::AgentFilePathNormalizer::from_runtime(runtime).map_err(|error| {
            ProtocolFailure::BackendContractViolation {
                message: format!("cannot establish the public path root: {}", error.message),
            }
        })?;
    let canonical =
        normalizer
            .normalize(&path)
            .map_err(|error| ProtocolFailure::BackendContractViolation {
                message: format!("backend returned an invalid source path: {}", error.message),
            })?;
    let canonical = std::path::PathBuf::from(canonical.into_rpc_path());
    let relative = canonical
        .strip_prefix(&normalizer.canonical_root)
        .map_err(|_| ProtocolFailure::BackendContractViolation {
            message: "backend returned a source path outside the workspace".to_string(),
        })?;
    let normalized = relative
        .components()
        .map(|component| component.as_os_str().to_str())
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| ProtocolFailure::BackendContractViolation {
            message: "backend returned a non-UTF-8 source path".to_string(),
        })?
        .join("/");
    WorkspaceKotlinPath::from_normalized(normalized).map_err(|message| {
        ProtocolFailure::BackendContractViolation {
            message: message.to_string(),
        }
    })
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum DiscoverResponse {
    #[serde(rename = "DISCOVER_SUCCESS")]
    Success { candidates: Vec<DiscoveryCandidate> },
    #[serde(rename = "DISCOVER_FAILURE")]
    Failure { message: String },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct DiscoveryCandidate {
    pub rank: u64,
    pub confidence: f64,
    pub symbol: SymbolInput,
    pub selector_handle: String,
    pub reasons: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum ResolveResponse {
    #[serde(rename = "RESOLVE_SUCCESS")]
    Success {
        symbol: SymbolInput,
        selector_handle: String,
    },
    #[serde(rename = "RESOLVE_NOT_FOUND")]
    NotFound,
    #[serde(rename = "RESOLVE_AMBIGUOUS")]
    Ambiguous { candidates: Vec<SelectableInput> },
    #[serde(rename = "RESOLVE_FAILURE")]
    Failure { message: String },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SelectableInput {
    pub symbol: SymbolInput,
    pub selector_handle: String,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum IdentityResponse {
    #[serde(rename = "AVAILABLE")]
    Available { identity: SymbolIdentityInput },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    Rejected {
        reason: SelectorRejectionReason,
        recovery: SelectorRecovery,
    },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum ReferencesResponse {
    #[serde(rename = "AVAILABLE")]
    Available {
        subject: SymbolIdentityInput,
        references: Vec<ReferenceInput>,
        evidence: RelationshipEvidence,
        #[serde(default)]
        page: Option<BackendPage>,
    },
    #[serde(rename = "DEGRADED")]
    Degraded {
        subject: SymbolIdentityInput,
        #[serde(default)]
        references: Vec<ReferenceInput>,
        evidence: RelationshipEvidence,
    },
    #[serde(rename = "SUBJECT_NOT_FOUND")]
    SubjectNotFound { selector: RelationshipSelectorInput },
    #[serde(rename = "SUBJECT_IDENTITY_MISMATCH")]
    SubjectIdentityMismatch {
        selector: RelationshipSelectorInput,
        actual: SymbolIdentityInput,
    },
    #[serde(rename = "UNSUPPORTED_SUBJECT_KIND")]
    UnsupportedSubjectKind {
        selector: RelationshipSelectorInput,
        subject: SymbolIdentityInput,
    },
    #[serde(rename = "CURSOR_STALE")]
    CursorStale,
    #[serde(rename = "CURSOR_INVALID")]
    CursorInvalid,
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorRejected {
        reason: SelectorRejectionReason,
        recovery: SelectorRecovery,
    },
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SymbolInput {
    pub fq_name: String,
    pub kind: SymbolKind,
    pub location: LocationInput,
    #[serde(default)]
    pub return_type: Option<String>,
    #[serde(default)]
    pub containing_declaration: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SymbolIdentityInput {
    pub fq_name: String,
    pub kind: SymbolKind,
    pub declaration_file: String,
    pub declaration_start_offset: u64,
    #[serde(default)]
    pub containing_type: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct LocationInput {
    pub file_path: String,
    pub start_offset: u64,
    pub end_offset: u64,
    pub start_line: u64,
    pub start_column: u64,
    pub preview: String,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum RelationshipEvidence {
    #[serde(rename = "COMPLETE")]
    Complete {
        cardinality: BackendCardinality,
        coverage: BackendCoverage,
    },
    #[serde(rename = "RESUMABLE")]
    Resumable {
        cardinality: BackendCardinality,
        coverage: BackendCoverage,
    },
    #[serde(rename = "LIMITED")]
    Limited {
        cardinality: BackendCardinality,
        coverage: BackendCoverage,
    },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub(super) enum BackendCardinality {
    #[serde(rename = "EXACT")]
    Exact { total_count: u64 },
    #[serde(rename = "KNOWN_MINIMUM")]
    KnownMinimum { known_minimum_count: u64 },
}

#[derive(Debug, Deserialize)]
pub(super) struct BackendCoverage {
    pub limitations: Vec<ProtocolLimitation>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct BackendPage {
    #[serde(default)]
    pub next_page_token: Option<String>,
}

impl SymbolInput {
    pub fn normalize(self, runtime: &AgentRuntimeArgs) -> Result<SymbolRecord, ProtocolFailure> {
        if self.fq_name.trim().is_empty()
            || self.location.start_offset > self.location.end_offset
            || self.location.start_line == 0
            || self.location.start_column == 0
            || self
                .containing_declaration
                .as_ref()
                .is_some_and(|value| value.trim().is_empty())
        {
            return Err(ProtocolFailure::BackendContractViolation {
                message: "backend returned an invalid symbol record".to_string(),
            });
        }
        Ok(SymbolRecord {
            fq_name: self.fq_name,
            kind: self.kind,
            location: self.location.normalize(runtime)?,
            return_type: self.return_type,
            containing_declaration: self.containing_declaration,
        })
    }
}

impl SymbolIdentityInput {
    pub fn normalize(self, runtime: &AgentRuntimeArgs) -> Result<SymbolIdentity, ProtocolFailure> {
        if self.fq_name.trim().is_empty()
            || self
                .containing_type
                .as_ref()
                .is_some_and(|value| value.trim().is_empty())
        {
            return Err(ProtocolFailure::BackendContractViolation {
                message: "backend returned an invalid symbol identity".to_string(),
            });
        }
        Ok(SymbolIdentity {
            fq_name: self.fq_name,
            kind: self.kind,
            declaration_file: normalize_path(runtime, self.declaration_file)?,
            declaration_start_offset: self.declaration_start_offset,
            containing_type: self.containing_type,
        })
    }
}

impl LocationInput {
    pub fn normalize(self, runtime: &AgentRuntimeArgs) -> Result<SourceLocation, ProtocolFailure> {
        if self.start_offset > self.end_offset || self.start_line == 0 || self.start_column == 0 {
            return Err(ProtocolFailure::BackendContractViolation {
                message: "backend returned an invalid source location".to_string(),
            });
        }
        Ok(SourceLocation {
            file_path: normalize_path(runtime, self.file_path)?,
            start_offset: self.start_offset,
            end_offset: self.end_offset,
            start_line: self.start_line,
            start_column: self.start_column,
            preview: self.preview,
        })
    }
}

impl RelationshipEvidence {
    pub fn into_protocol(self) -> (ProtocolCardinality, Vec<ProtocolLimitation>, bool) {
        let (cardinality, coverage, complete) = match self {
            Self::Complete {
                cardinality,
                coverage,
            } => (cardinality, coverage, true),
            Self::Resumable {
                cardinality,
                coverage,
            }
            | Self::Limited {
                cardinality,
                coverage,
            } => (cardinality, coverage, false),
        };
        let cardinality = match cardinality {
            BackendCardinality::Exact { total_count } => {
                ProtocolCardinality::Exact { count: total_count }
            }
            BackendCardinality::KnownMinimum {
                known_minimum_count,
            } => ProtocolCardinality::KnownMinimum {
                count: known_minimum_count,
            },
        };
        (cardinality, coverage.limitations, complete)
    }
}

pub(super) fn selector_rejection(
    reason: SelectorRejectionReason,
    recovery: SelectorRecovery,
) -> ProtocolFailure {
    if reason.recovery() != recovery {
        return ProtocolFailure::BackendContractViolation {
            message: "selector rejection named an invalid recovery action".to_string(),
        };
    }
    ProtocolFailure::SelectorRejected { reason, recovery }
}

pub(super) fn reference_params(
    selector: &IssuedSymbolSelector,
    page_token: Option<String>,
) -> Value {
    let mut params = json!({
        "selectorHandle": selector.as_str(),
        "includeDeclaration": false,
        "maxResults": 200,
    });
    if let Some(page_token) = page_token {
        params["pageToken"] = Value::String(page_token);
    }
    params
}

pub(super) fn page(
    cardinality: ProtocolCardinality,
    returned: usize,
    continuation: Option<String>,
) -> ProtocolPage {
    ProtocolPage {
        cardinality,
        returned,
        continuation,
    }
}
