use serde::{Deserialize, Serialize};
use uuid::{Uuid, Version};

use super::traversal_types::ContainingSymbol;

#[derive(Debug)]
pub(super) enum PublicOperation {
    SymbolSearch(SymbolSearchRequest),
    SymbolResolve(SymbolResolveRequest),
    SymbolShow(SymbolShowInput),
    RelationReferences(RelationReferencesInput),
    RelationCallsIncoming(ExactSymbolRequest),
    RelationCallsOutgoing(ExactSymbolRequest),
    RelationImplementations(ExactSymbolRequest),
    RelationHierarchySupertypes(ExactSymbolRequest),
    RelationHierarchySubtypes(ExactSymbolRequest),
}

#[derive(Debug)]
pub(super) struct SymbolSearchRequest {
    pub query: SymbolQuery,
}

#[derive(Debug)]
pub(super) struct SymbolResolveRequest {
    pub query: SymbolQuery,
}

#[derive(Debug)]
pub(super) struct SymbolShowInput {
    pub selector: UntrustedSymbolSelector,
}

#[derive(Debug)]
pub(super) struct RelationReferencesInput {
    pub selector: UntrustedSymbolSelector,
    pub continuation: Option<String>,
}

#[derive(Debug)]
pub(super) struct ExactSymbolRequest {
    pub selector: UntrustedSymbolSelector,
    pub continuation: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub(super) struct SymbolQuery(String);

impl SymbolQuery {
    pub fn parse(value: String) -> Result<Self, &'static str> {
        if value.trim().is_empty() {
            return Err("query must not be blank");
        }
        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct UntrustedSymbolSelector(String);

impl UntrustedSymbolSelector {
    pub fn parse(value: String) -> Result<Self, &'static str> {
        if value.is_empty() {
            return Err("selector must not be empty");
        }
        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub(crate) struct IssuedSymbolSelector(String);

impl IssuedSymbolSelector {
    pub(super) fn from_backend(value: String) -> Result<Self, &'static str> {
        if value.is_empty() {
            return Err("backend-issued selector must not be empty");
        }
        Ok(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug)]
pub(crate) struct SymbolSelector {
    issued: IssuedSymbolSelector,
    identity: SymbolIdentity,
}

impl SymbolSelector {
    pub(super) fn authenticated(
        input: UntrustedSymbolSelector,
        identity: SymbolIdentity,
    ) -> Result<Self, &'static str> {
        Ok(Self {
            issued: IssuedSymbolSelector::from_backend(input.0)?,
            identity,
        })
    }

    pub(super) fn issued(&self) -> &IssuedSymbolSelector {
        &self.issued
    }

    pub(super) fn identity(&self) -> &SymbolIdentity {
        &self.identity
    }

    pub(crate) fn as_str(&self) -> &str {
        self.issued.as_str()
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub(crate) struct WorkspaceKotlinPath(String);

impl WorkspaceKotlinPath {
    pub(crate) fn from_normalized(value: String) -> Result<Self, &'static str> {
        if value.is_empty()
            || value.starts_with('/')
            || value.contains('\\')
            || value
                .split('/')
                .any(|segment| matches!(segment, "" | "." | ".."))
        {
            return Err("public source path must be workspace-relative with forward slashes");
        }
        Ok(Self(value))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct PlanId(Uuid);

impl PlanId {
    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        parse_v4_id(value)
            .map(Self)
            .ok_or("Plan IDs must be canonical lowercase version-4 UUIDs returned by Kast.")
    }

    pub(crate) fn uuid(self) -> Uuid {
        self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct RecoveryId(Uuid);

impl RecoveryId {
    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        parse_v4_id(value)
            .map(Self)
            .ok_or("Recovery IDs must be canonical lowercase version-4 UUIDs returned by Kast.")
    }

    pub(crate) fn uuid(self) -> Uuid {
        self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ExternalFailureId(String);

impl ExternalFailureId {
    pub(crate) fn parse(value: String) -> Result<Self, &'static str> {
        Uuid::parse_str(&value)
            .ok()
            .filter(|parsed| parsed.hyphenated().to_string() == value)
            .map(|_| Self(value))
            .ok_or("External failure IDs must be canonical lowercase UUIDs returned by Kast.")
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

fn parse_v4_id(value: &str) -> Option<Uuid> {
    Uuid::parse_str(value)
        .ok()
        .filter(|id| id.get_version() == Some(Version::Random))
        .filter(|id| id.hyphenated().to_string() == value)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, serde::Deserialize, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(super) enum SymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SourceLocation {
    pub file_path: WorkspaceKotlinPath,
    pub start_offset: u64,
    pub end_offset: u64,
    pub start_line: u64,
    pub start_column: u64,
    pub preview: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SymbolRecord {
    pub fq_name: String,
    pub kind: SymbolKind,
    pub location: SourceLocation,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub return_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub containing_declaration: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SymbolIdentity {
    pub fq_name: String,
    pub kind: SymbolKind,
    pub declaration_file: WorkspaceKotlinPath,
    pub declaration_start_offset: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub containing_type: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RelationshipSelectorInput {
    pub fq_name: String,
    pub declaration_file: String,
    pub declaration_start_offset: u64,
    #[serde(default)]
    pub kind: Option<SymbolKind>,
    #[serde(default)]
    pub containing_type: Option<String>,
}

impl RelationshipSelectorInput {
    pub(super) fn matches(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
        expected: &SymbolIdentity,
    ) -> Result<bool, super::protocol::ProtocolFailure> {
        if self.fq_name.trim().is_empty()
            || self
                .containing_type
                .as_ref()
                .is_some_and(|value| value.trim().is_empty())
        {
            return Err(super::protocol::ProtocolFailure::BackendContractViolation {
                message: "backend returned an invalid relationship selector".to_string(),
            });
        }
        Ok(self.fq_name == expected.fq_name
            && super::backend::normalize_path(runtime, self.declaration_file)?
                == expected.declaration_file
            && self.declaration_start_offset == expected.declaration_start_offset
            && self.kind.is_none_or(|kind| kind == expected.kind)
            && self
                .containing_type
                .as_ref()
                .is_none_or(|value| Some(value) == expected.containing_type.as_ref()))
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SelectableSymbol {
    pub symbol: SymbolRecord,
    pub selector: IssuedSymbolSelector,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SymbolMatch {
    pub rank: u64,
    pub confidence: f64,
    pub symbol: SymbolRecord,
    pub selector: IssuedSymbolSelector,
    pub reasons: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ReferenceOccurrence {
    pub location: SourceLocation,
    pub containing_symbol: ContainingSymbol,
}

pub(super) fn subject_not_found_failure(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    evidence_selector: RelationshipSelectorInput,
) -> Result<super::protocol::ProtocolFailure, super::protocol::ProtocolFailure> {
    require_relationship_selector(runtime, selector, evidence_selector)?;
    Ok(super::protocol::ProtocolFailure::SubjectNotFound {
        selector: selector.issued().clone(),
    })
}

pub(super) fn subject_identity_mismatch_failure(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    evidence_selector: RelationshipSelectorInput,
    actual: super::backend::SymbolIdentityInput,
) -> Result<super::protocol::ProtocolFailure, super::protocol::ProtocolFailure> {
    require_relationship_selector(runtime, selector, evidence_selector)?;
    let actual = actual.normalize(runtime)?;
    if &actual == selector.identity() {
        return Err(invalid_relationship_evidence(
            "identity mismatch evidence repeated the authenticated subject",
        ));
    }
    Ok(super::protocol::ProtocolFailure::SubjectIdentityMismatch {
        selector: selector.issued().clone(),
        actual,
    })
}

pub(super) fn unsupported_subject_kind_failure(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    evidence_selector: RelationshipSelectorInput,
    subject: super::backend::SymbolIdentityInput,
    supports: impl FnOnce(SymbolKind) -> bool,
) -> Result<super::protocol::ProtocolFailure, super::protocol::ProtocolFailure> {
    require_relationship_selector(runtime, selector, evidence_selector)?;
    let subject = subject.normalize(runtime)?;
    if &subject != selector.identity() || supports(subject.kind) {
        return Err(invalid_relationship_evidence(
            "unsupported-kind evidence did not prove the authenticated subject and family",
        ));
    }
    Ok(super::protocol::ProtocolFailure::UnsupportedSubjectKind {
        selector: selector.issued().clone(),
        subject,
    })
}

fn require_relationship_selector(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    evidence_selector: RelationshipSelectorInput,
) -> Result<(), super::protocol::ProtocolFailure> {
    if evidence_selector.matches(runtime, selector.identity())? {
        Ok(())
    } else {
        Err(invalid_relationship_evidence(
            "relationship outcome selector did not match the authenticated subject",
        ))
    }
}

fn invalid_relationship_evidence(message: &str) -> super::protocol::ProtocolFailure {
    super::protocol::ProtocolFailure::BackendContractViolation {
        message: message.to_string(),
    }
}
