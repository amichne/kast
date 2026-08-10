use super::backend::{
    DiscoverResponse, IdentityResponse, ReferencesResponse, ResolveResponse, SelectableInput, page,
    reference_params, request, selector_rejection,
};
use super::domain::{
    IssuedSymbolSelector, PublicOperation, SelectableSymbol, SymbolMatch, SymbolSelector,
    UntrustedSymbolSelector, subject_identity_mismatch_failure, subject_not_found_failure,
    unsupported_subject_kind_failure,
};
use super::protocol::{OperationId, ProtocolEnvelope, ProtocolFailure, ProtocolResult};
use crate::cli::AgentRuntimeArgs;
use serde::de::DeserializeOwned;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::path::PathBuf;

pub(super) fn execute(workspace_root: PathBuf, operation: PublicOperation) -> ProtocolEnvelope {
    let runtime = AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
    };
    match operation {
        PublicOperation::SymbolSearch(request) => symbol_search(&runtime, request.query),
        PublicOperation::SymbolResolve(request) => symbol_resolve(&runtime, request.query),
        PublicOperation::SymbolShow(input) => symbol_show(&runtime, input.selector),
        PublicOperation::RelationReferences(input) => {
            relation_references(&runtime, input.selector, input.continuation)
        }
        PublicOperation::RelationCallsIncoming(input) => super::traversal::execute(
            &runtime,
            input,
            super::traversal::TraversalOperation::CallsIncoming,
        ),
        PublicOperation::RelationCallsOutgoing(input) => super::traversal::execute(
            &runtime,
            input,
            super::traversal::TraversalOperation::CallsOutgoing,
        ),
        PublicOperation::RelationImplementations(input) => super::traversal::execute(
            &runtime,
            input,
            super::traversal::TraversalOperation::Implementations,
        ),
        PublicOperation::RelationHierarchySupertypes(input) => super::traversal::execute(
            &runtime,
            input,
            super::traversal::TraversalOperation::HierarchySupertypes,
        ),
        PublicOperation::RelationHierarchySubtypes(input) => super::traversal::execute(
            &runtime,
            input,
            super::traversal::TraversalOperation::HierarchySubtypes,
        ),
    }
}

fn symbol_search(
    runtime: &AgentRuntimeArgs,
    query: super::domain::SymbolQuery,
) -> ProtocolEnvelope {
    let operation = OperationId::SymbolSearch;
    let result = request(
        runtime,
        "symbol/discover",
        json!({
            "symbol": query.as_str(),
            "maxResults": 10,
            "includeDeclarationScope": false,
        }),
    )
    .and_then(parse::<DiscoverResponse>);
    let response = match result {
        Ok(response) => response,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    let DiscoverResponse::Success { candidates } = response else {
        let DiscoverResponse::Failure { message } = response else {
            unreachable!()
        };
        return ProtocolEnvelope::rejected(
            operation,
            ProtocolFailure::BackendRejected {
                code: "SYMBOL_DISCOVERY_FAILED".to_string(),
                message,
            },
        );
    };
    let matches = candidates
        .into_iter()
        .map(|candidate| {
            Ok(SymbolMatch {
                rank: candidate.rank,
                confidence: candidate.confidence,
                symbol: candidate.symbol.normalize(runtime)?,
                selector: issued_selector(candidate.selector_handle)?,
                reasons: candidate.reasons,
            })
        })
        .collect::<Result<Vec<_>, ProtocolFailure>>();
    match matches {
        Ok(matches) => ProtocolEnvelope::complete(operation, ProtocolResult::Matches { matches }),
        Err(failure) => ProtocolEnvelope::rejected(operation, failure),
    }
}

fn symbol_resolve(
    runtime: &AgentRuntimeArgs,
    query: super::domain::SymbolQuery,
) -> ProtocolEnvelope {
    let operation = OperationId::SymbolResolve;
    let result = request(
        runtime,
        "symbol/resolve",
        json!({
            "symbol": query.as_str(),
            "includeDeclarationScope": false,
            "includeDocumentation": false,
            "includeSurroundingMembers": false,
        }),
    )
    .and_then(parse::<ResolveResponse>);
    let response = match result {
        Ok(response) => response,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    let result = match response {
        ResolveResponse::Success {
            symbol,
            selector_handle,
        } => symbol.normalize(runtime).and_then(|symbol| {
            Ok(ProtocolResult::Resolved {
                symbol,
                selector: issued_selector(selector_handle)?,
            })
        }),
        ResolveResponse::NotFound => Ok(ProtocolResult::NotFound { query }),
        ResolveResponse::Ambiguous { candidates } => candidates
            .into_iter()
            .map(|candidate| selectable_symbol(runtime, candidate))
            .collect::<Result<Vec<_>, _>>()
            .map(|candidates| ProtocolResult::Ambiguous { query, candidates }),
        ResolveResponse::Failure { message } => Err(ProtocolFailure::BackendRejected {
            code: "SYMBOL_RESOLUTION_FAILED".to_string(),
            message,
        }),
    };
    match result {
        Ok(result) => ProtocolEnvelope::complete(operation, result),
        Err(failure) => ProtocolEnvelope::rejected(operation, failure),
    }
}

fn symbol_show(runtime: &AgentRuntimeArgs, input: UntrustedSymbolSelector) -> ProtocolEnvelope {
    let operation = OperationId::SymbolShow;
    let selector = match authenticate_selector(runtime, input, "IDENTITY") {
        Ok(selector) => selector,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    ProtocolEnvelope::complete(
        operation,
        ProtocolResult::Symbol {
            selector: selector.issued().clone(),
            symbol: selector.identity().clone(),
        },
    )
}

fn relation_references(
    runtime: &AgentRuntimeArgs,
    input: UntrustedSymbolSelector,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    let operation = OperationId::RelationReferences;
    let reference_ready =
        match crate::runtime::demand_reference_ready_runtime(runtime.workspace_root.clone()) {
            Ok(ready) => ready,
            Err(error) => {
                return ProtocolEnvelope::rejected(
                    operation,
                    ProtocolFailure::BackendRejected {
                        code: error.code.to_string(),
                        message: error.message,
                    },
                );
            }
        };
    debug_assert_eq!(
        runtime.workspace_root.as_deref(),
        Some(reference_ready.workspace_root())
    );
    let selector = match authenticate_selector(runtime, input, "REFERENCES") {
        Ok(selector) => selector,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    let raw_continuation = match continuation
        .map(|value| decode_reference_continuation(runtime, selector.issued(), &value))
        .transpose()
    {
        Ok(value) => value,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    let response = request(
        runtime,
        "symbol/references",
        reference_params(selector.issued(), raw_continuation),
    )
    .and_then(parse::<ReferencesResponse>);
    let response = match response {
        Ok(response) => response,
        Err(failure) => return ProtocolEnvelope::rejected(operation, failure),
    };
    match references_result(runtime, selector, response) {
        Ok((result, true)) => ProtocolEnvelope::complete(operation, result),
        Ok((result, false)) => ProtocolEnvelope::qualified(operation, result),
        Err(failure) => ProtocolEnvelope::rejected(operation, failure),
    }
}

fn references_result(
    runtime: &AgentRuntimeArgs,
    selector: SymbolSelector,
    response: ReferencesResponse,
) -> Result<(ProtocolResult, bool), ProtocolFailure> {
    let (subject, references, evidence, next_page, available) = match response {
        ReferencesResponse::Available {
            subject,
            references,
            evidence,
            page,
        } => (
            subject,
            references,
            evidence,
            page.and_then(|page| page.next_page_token),
            true,
        ),
        ReferencesResponse::Degraded {
            subject,
            references,
            evidence,
        } => (subject, references, evidence, None, false),
        ReferencesResponse::SubjectNotFound { selector: evidence } => {
            return Err(subject_not_found_failure(runtime, &selector, evidence)?);
        }
        ReferencesResponse::SubjectIdentityMismatch {
            selector: evidence,
            actual,
        } => {
            return Err(subject_identity_mismatch_failure(
                runtime, &selector, evidence, actual,
            )?);
        }
        ReferencesResponse::UnsupportedSubjectKind {
            selector: evidence,
            subject,
        } => {
            return Err(unsupported_subject_kind_failure(
                runtime,
                &selector,
                evidence,
                subject,
                |kind| kind != super::domain::SymbolKind::Unknown,
            )?);
        }
        ReferencesResponse::CursorStale => return Err(ProtocolFailure::ContinuationStale),
        ReferencesResponse::CursorInvalid => return Err(ProtocolFailure::ContinuationInvalid),
        ReferencesResponse::SelectorRejected { reason, recovery } => {
            return Err(selector_rejection(reason, recovery));
        }
    };
    let subject = subject.normalize(runtime)?;
    if &subject != selector.identity() {
        return Err(ProtocolFailure::SubjectIdentityMismatch {
            selector: selector.issued().clone(),
            actual: subject,
        });
    }
    let references = references
        .into_iter()
        .map(|reference| reference.normalize(runtime))
        .collect::<Result<Vec<_>, _>>()?;
    let (cardinality, limitations, evidence_complete) = evidence.into_protocol();
    let continuation = next_page
        .map(|raw| issue_reference_continuation(runtime, selector.issued(), &raw))
        .transpose()?;
    let page = page(cardinality, references.len(), continuation);
    Ok((
        ProtocolResult::References {
            selector: selector.issued().clone(),
            subject,
            references,
            page,
            limitations,
        },
        available && evidence_complete,
    ))
}

pub(super) fn authenticate_selector(
    runtime: &AgentRuntimeArgs,
    input: UntrustedSymbolSelector,
    family: &'static str,
) -> Result<SymbolSelector, ProtocolFailure> {
    let response = request(
        runtime,
        "selector/identity",
        json!({
            "selectorHandle": input.as_str(),
            "family": family,
        }),
    )
    .and_then(parse::<IdentityResponse>)?;
    match response {
        IdentityResponse::Available { identity } => {
            let identity = identity.normalize(runtime)?;
            SymbolSelector::authenticated(input, identity).map_err(|message| {
                ProtocolFailure::BackendContractViolation {
                    message: message.to_string(),
                }
            })
        }
        IdentityResponse::Rejected { reason, recovery } => {
            Err(selector_rejection(reason, recovery))
        }
    }
}

fn selectable_symbol(
    runtime: &AgentRuntimeArgs,
    candidate: SelectableInput,
) -> Result<SelectableSymbol, ProtocolFailure> {
    Ok(SelectableSymbol {
        symbol: candidate.symbol.normalize(runtime)?,
        selector: issued_selector(candidate.selector_handle)?,
    })
}

fn issued_selector(value: String) -> Result<IssuedSymbolSelector, ProtocolFailure> {
    IssuedSymbolSelector::from_backend(value).map_err(|message| {
        ProtocolFailure::BackendContractViolation {
            message: message.to_string(),
        }
    })
}

fn parse<T: DeserializeOwned>(value: serde_json::Value) -> Result<T, ProtocolFailure> {
    serde_json::from_value(value).map_err(|error| ProtocolFailure::BackendContractViolation {
        message: error.to_string(),
    })
}

include!("reference_continuation.rs");
