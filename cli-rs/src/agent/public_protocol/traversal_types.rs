use super::backend::{LocationInput, SymbolIdentityInput};
use super::domain::{SourceLocation, SymbolIdentity, SymbolSelector};
use super::protocol::ProtocolFailure;
use super::traversal::TraversalOperation;
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const MAX_CONTINUATION_LENGTH: usize = 4_096;

#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub(super) enum RelationRecordInput {
    Call(CallRecordInput),
    Implementation(ImplementationRecordInput),
    Hierarchy(HierarchyRecordInput),
}

impl RelationRecordInput {
    pub(super) fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<RelationRecord, ProtocolFailure> {
        match self {
            Self::Call(record) => record.normalize(runtime),
            Self::Implementation(record) => record.normalize(runtime),
            Self::Hierarchy(record) => record.normalize(runtime),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct CallRecordInput {
    relation: CallKind,
    related_symbol: SymbolIdentityInput,
    call_site: LocationInput,
    depth: u64,
    containing_symbol: ContainingSymbolInput,
}

impl CallRecordInput {
    fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<RelationRecord, ProtocolFailure> {
        if self.depth == 0 {
            return Err(contract_violation("call relation depth was zero"));
        }
        let fields = RelationFields {
            related_symbol: self.related_symbol.normalize(runtime)?,
            location: self.call_site.normalize(runtime)?,
            depth: Some(self.depth),
            containing_symbol: Some(self.containing_symbol.normalize(runtime)?),
        };
        Ok(match self.relation {
            CallKind::Caller => RelationRecord::IncomingCall { fields },
            CallKind::Callee => RelationRecord::OutgoingCall { fields },
        })
    }
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CallKind {
    Caller,
    Callee,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ImplementationRecordInput {
    relation: ImplementationKind,
    implementation: SymbolIdentityInput,
    declaration_location: LocationInput,
}

impl ImplementationRecordInput {
    fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<RelationRecord, ProtocolFailure> {
        let _ = self.relation;
        Ok(RelationRecord::Implementation {
            fields: RelationFields {
                related_symbol: self.implementation.normalize(runtime)?,
                location: self.declaration_location.normalize(runtime)?,
                depth: None,
                containing_symbol: None,
            },
        })
    }
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ImplementationKind {
    Implementation,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct HierarchyRecordInput {
    relation: HierarchyKind,
    related_symbol: SymbolIdentityInput,
    declaration_location: LocationInput,
    depth: u64,
}

impl HierarchyRecordInput {
    fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<RelationRecord, ProtocolFailure> {
        if self.depth == 0 {
            return Err(contract_violation("hierarchy relation depth was zero"));
        }
        let fields = RelationFields {
            related_symbol: self.related_symbol.normalize(runtime)?,
            location: self.declaration_location.normalize(runtime)?,
            depth: Some(self.depth),
            containing_symbol: None,
        };
        Ok(match self.relation {
            HierarchyKind::Supertype => RelationRecord::Supertype { fields },
            HierarchyKind::Subtype => RelationRecord::Subtype { fields },
        })
    }
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum HierarchyKind {
    Supertype,
    Subtype,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum ContainingSymbolInput {
    #[serde(rename = "KNOWN")]
    Known { symbol: SymbolIdentityInput },
    #[serde(rename = "TOP_LEVEL")]
    TopLevel,
    #[serde(rename = "UNAVAILABLE")]
    Unavailable { reason: ContainingSymbolUnavailable },
}

impl ContainingSymbolInput {
    fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<ContainingSymbol, ProtocolFailure> {
        Ok(match self {
            Self::Known { symbol } => ContainingSymbol::Known {
                symbol: symbol.normalize(runtime)?,
            },
            Self::TopLevel => ContainingSymbol::TopLevel,
            Self::Unavailable { reason } => ContainingSymbol::Unavailable { reason },
        })
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
enum ContainingSymbolUnavailable {
    #[serde(rename(serialize = "no-semantic-owner", deserialize = "NO_SEMANTIC_OWNER"))]
    NoSemanticOwner,
    #[serde(rename(
        serialize = "unsupported-owner-kind",
        deserialize = "UNSUPPORTED_OWNER_KIND"
    ))]
    UnsupportedOwnerKind,
    #[serde(rename(
        serialize = "identity-unavailable",
        deserialize = "IDENTITY_UNAVAILABLE"
    ))]
    IdentityUnavailable,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase"
)]
enum ContainingSymbol {
    Known { symbol: SymbolIdentity },
    TopLevel,
    Unavailable { reason: ContainingSymbolUnavailable },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RelationFields {
    related_symbol: SymbolIdentity,
    location: SourceLocation,
    #[serde(skip_serializing_if = "Option::is_none")]
    depth: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    containing_symbol: Option<ContainingSymbol>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase"
)]
pub(super) enum RelationRecord {
    IncomingCall {
        #[serde(flatten)]
        fields: RelationFields,
    },
    OutgoingCall {
        #[serde(flatten)]
        fields: RelationFields,
    },
    Implementation {
        #[serde(flatten)]
        fields: RelationFields,
    },
    Supertype {
        #[serde(flatten)]
        fields: RelationFields,
    },
    Subtype {
        #[serde(flatten)]
        fields: RelationFields,
    },
}

pub(super) fn issue_continuation(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    operation: TraversalOperation,
    raw: &str,
) -> Result<String, ProtocolFailure> {
    validate_raw_continuation(raw)?;
    Ok(format!(
        "kpc1.{}.{}.{}",
        operation.tag(),
        continuation_fingerprint(runtime, selector, operation)?,
        URL_SAFE_NO_PAD.encode(raw.as_bytes())
    ))
}

pub(super) fn decode_continuation(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    operation: TraversalOperation,
    value: &str,
) -> Result<String, ProtocolFailure> {
    let fields = value.split('.').collect::<Vec<_>>();
    if fields.len() != 4 || fields[0] != "kpc1" {
        return Err(ProtocolFailure::ContinuationInvalid);
    }
    if fields[1] != operation.tag()
        || fields[2] != continuation_fingerprint(runtime, selector, operation)?
    {
        return Err(ProtocolFailure::ContinuationMismatch);
    }
    let bytes = URL_SAFE_NO_PAD
        .decode(fields[3])
        .map_err(|_| ProtocolFailure::ContinuationInvalid)?;
    let raw = String::from_utf8(bytes).map_err(|_| ProtocolFailure::ContinuationInvalid)?;
    if URL_SAFE_NO_PAD.encode(raw.as_bytes()) != fields[3] {
        return Err(ProtocolFailure::ContinuationInvalid);
    }
    validate_raw_continuation(&raw)?;
    Ok(raw)
}

fn continuation_fingerprint(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: &SymbolSelector,
    operation: TraversalOperation,
) -> Result<String, ProtocolFailure> {
    let root = runtime
        .workspace_root
        .as_ref()
        .ok_or_else(|| contract_violation("exact traversal requires one workspace root"))?;
    let mut digest = Sha256::new();
    digest.update(root.as_os_str().as_encoded_bytes());
    digest.update(b"\n");
    digest.update(operation.tag().as_bytes());
    digest.update(b"\n");
    digest.update(selector.issued().as_str().as_bytes());
    Ok(hex::encode(digest.finalize())[..24].to_string())
}

fn validate_raw_continuation(value: &str) -> Result<(), ProtocolFailure> {
    if value.is_empty()
        || value.len() > MAX_CONTINUATION_LENGTH
        || !value.is_ascii()
        || value.chars().any(char::is_control)
    {
        return Err(ProtocolFailure::ContinuationInvalid);
    }
    Ok(())
}

fn contract_violation(message: &str) -> ProtocolFailure {
    ProtocolFailure::BackendContractViolation {
        message: message.to_string(),
    }
}
