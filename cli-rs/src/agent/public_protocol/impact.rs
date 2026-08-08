use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::PathBuf;

pub(super) fn execute(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> super::protocol::ProtocolEnvelope {
    use super::protocol::{OperationId, ProtocolEnvelope, ProtocolFailure};
    use crate::cli::{
        AgentCommand, AgentImpactArgs, AgentImpactPageToken, AgentImpactViewArgs,
        AgentRelationLimit, AgentReusableSymbolSelectorArgs, AgentRuntimeArgs, AgentSelectorHandle,
    };

    let operation = OperationId::GraphImpact;
    let selector_handle = match selector.parse::<AgentSelectorHandle>() {
        Ok(selector) => selector,
        Err(reason) => {
            return ProtocolEnvelope::rejected(
                operation,
                ProtocolFailure::InvalidInput {
                    field: "selector",
                    reason,
                },
            );
        }
    };
    let page_token = match continuation
        .map(|value| value.parse::<AgentImpactPageToken>())
        .transpose()
    {
        Ok(token) => token,
        Err(_) => {
            return ProtocolEnvelope::rejected(operation, ProtocolFailure::ContinuationInvalid);
        }
    };
    let runtime = AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
        ..Default::default()
    };
    let limit = "200"
        .parse::<AgentRelationLimit>()
        .expect("public impact limit is inside the typed range");
    let envelope = super::super::execute_projected(AgentCommand::Impact(AgentImpactArgs {
        runtime: runtime.clone(),
        selector: AgentReusableSymbolSelectorArgs {
            symbol: None,
            declaration_file: None,
            declaration_start_offset: None,
            kind: None,
            containing_type: None,
            selector_handle: Some(selector_handle),
        },
        depth: Default::default(),
        limit,
        page_token,
        view: AgentImpactViewArgs::default(),
    }));
    if !envelope.ok {
        let error = envelope.error.unwrap_or_else(|| super::super::AgentError {
            code: "IMPACT_REJECTED".to_string(),
            message: "Impact was rejected without typed details.".to_string(),
            details: Default::default(),
        });
        let failure = match error.code.as_str() {
            "IMPACT_PAGE_TOKEN_INVALID" => ProtocolFailure::ContinuationInvalid,
            "IMPACT_PAGE_TOKEN_MISMATCH" => ProtocolFailure::ContinuationMismatch,
            _ => ProtocolFailure::BackendRejected {
                code: error.code,
                message: error.message,
            },
        };
        return ProtocolEnvelope::rejected(operation, failure);
    }
    let Some(result) = envelope.result else {
        return ProtocolEnvelope::rejected(
            operation,
            contract_violation("impact returned no result"),
        );
    };
    let result = match serde_json::from_value::<ImpactProjection>(result) {
        Ok(result) => result,
        Err(error) => {
            return ProtocolEnvelope::rejected(
                operation,
                contract_violation(&format!("impact result violated its contract: {error}")),
            );
        }
    };
    normalize_impact(&runtime, selector, result)
}

#[derive(Debug, Deserialize)]
#[serde(tag = "outcome", rename_all_fields = "camelCase")]
enum ImpactProjection {
    #[serde(rename = "AVAILABLE")]
    Available {
        query: Box<ImpactQueryInput>,
        total_count: u64,
        returned_count: usize,
        truncated: bool,
        nodes: Vec<ImpactNodeInput>,
        confidence: ImpactConfidence,
        #[serde(default)]
        next_page_token: Option<String>,
    },
    #[serde(rename = "DEGRADED")]
    Degraded { reason: String },
    #[serde(rename = "SELECTOR_HANDLE_REJECTED")]
    SelectorRejected {
        reason: super::protocol::SelectorRejectionReason,
        recovery: super::protocol::SelectorRecovery,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImpactQueryInput {
    symbol: String,
    subject: super::backend::SymbolIdentityInput,
    offset: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImpactNodeInput {
    source_path: String,
    depth: u64,
    via_target_fq_name: String,
    #[serde(default)]
    edge_kind: Option<String>,
    occurrence_count: i64,
    confidence: ImpactNodeConfidence,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ImpactNode {
    source_path: super::domain::WorkspaceKotlinPath,
    depth: u64,
    via_target_fq_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    edge_kind: Option<String>,
    occurrence_count: i64,
    confidence: ImpactNodeConfidence,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImpactNodeConfidence {
    level: String,
    index_completeness: f64,
    semantic_basis: String,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ImpactConfidence {
    levels: BTreeMap<String, u64>,
    semantic_bases: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    minimum_index_completeness: Option<f64>,
}

fn normalize_impact(
    runtime: &crate::cli::AgentRuntimeArgs,
    selector: String,
    result: ImpactProjection,
) -> super::protocol::ProtocolEnvelope {
    use super::protocol::{OperationId, ProtocolEnvelope, ProtocolFailure, ProtocolResult};

    let operation = OperationId::GraphImpact;
    let selector = match super::domain::IssuedSymbolSelector::from_backend(selector) {
        Ok(selector) => selector,
        Err(reason) => {
            return ProtocolEnvelope::rejected(operation, contract_violation(reason));
        }
    };
    let normalized = match result {
        ImpactProjection::Available {
            query,
            total_count,
            returned_count,
            truncated,
            nodes,
            confidence,
            next_page_token,
        } => {
            let subject = match query.subject.normalize(runtime) {
                Ok(subject) if subject.fq_name == query.symbol => subject,
                Ok(actual) => {
                    return ProtocolEnvelope::rejected(
                        operation,
                        ProtocolFailure::SubjectIdentityMismatch {
                            selector: selector.clone(),
                            actual,
                        },
                    );
                }
                Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
            };
            if returned_count != nodes.len()
                || truncated != next_page_token.is_some()
                || total_count < query.offset.saturating_add(returned_count as u64)
                || truncated && total_count <= query.offset.saturating_add(returned_count as u64)
                || !valid_impact_confidence(&confidence)
            {
                return ProtocolEnvelope::rejected(
                    operation,
                    contract_violation("impact page evidence was inconsistent"),
                );
            }
            let nodes = match nodes
                .into_iter()
                .map(|node| node.normalize(runtime))
                .collect::<Result<Vec<_>, _>>()
            {
                Ok(nodes) => nodes,
                Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
            };
            ProtocolResult::Impact {
                selector,
                subject,
                nodes,
                page: super::backend::page(
                    super::protocol::ProtocolCardinality::Exact { count: total_count },
                    returned_count,
                    next_page_token,
                ),
                confidence,
                limitations: Vec::new(),
            }
        }
        ImpactProjection::Degraded { reason } => ProtocolResult::ImpactQualified {
            selector,
            limitations: vec![if reason == "IMPACT_INDEX_IDENTITY_UNAVAILABLE" {
                super::protocol::ProtocolLimitation::IdentityUnproven
            } else {
                super::protocol::ProtocolLimitation::BackendIncomplete
            }],
        },
        ImpactProjection::SelectorRejected { reason, recovery } => {
            return ProtocolEnvelope::rejected(
                operation,
                super::backend::selector_rejection(reason, recovery),
            );
        }
    };
    if matches!(normalized, ProtocolResult::ImpactQualified { .. }) {
        ProtocolEnvelope::qualified(operation, normalized)
    } else {
        ProtocolEnvelope::complete(operation, normalized)
    }
}

impl ImpactNodeInput {
    fn normalize(
        self,
        runtime: &crate::cli::AgentRuntimeArgs,
    ) -> Result<ImpactNode, super::protocol::ProtocolFailure> {
        if self.via_target_fq_name.trim().is_empty()
            || self.occurrence_count <= 0
            || !valid_node_confidence(&self.confidence)
        {
            return Err(contract_violation("impact node evidence was invalid"));
        }
        Ok(ImpactNode {
            source_path: super::backend::normalize_path(runtime, self.source_path)?,
            depth: self.depth,
            via_target_fq_name: self.via_target_fq_name,
            edge_kind: self.edge_kind,
            occurrence_count: self.occurrence_count,
            confidence: self.confidence,
        })
    }
}

fn valid_node_confidence(confidence: &ImpactNodeConfidence) -> bool {
    !confidence.level.trim().is_empty()
        && !confidence.semantic_basis.trim().is_empty()
        && confidence.index_completeness.is_finite()
        && (0.0..=1.0).contains(&confidence.index_completeness)
}

fn valid_impact_confidence(confidence: &ImpactConfidence) -> bool {
    confidence
        .minimum_index_completeness
        .is_none_or(|value| value.is_finite() && (0.0..=1.0).contains(&value))
}

fn contract_violation(message: &str) -> super::protocol::ProtocolFailure {
    super::protocol::ProtocolFailure::BackendContractViolation {
        message: message.to_string(),
    }
}
