mod backend;
mod domain;
mod execution;
mod graph;
mod impact;
mod protocol;
mod registry;
mod traversal;
mod traversal_types;

pub(crate) use graph::{
    GraphNodesPageToken, UntrustedGraphNodeSelector, authenticate_graph_node_selector,
    graph_workspace_fingerprint, issue_graph_node_selector,
};

use domain::{
    ExactSymbolRequest, PublicOperation, RelationReferencesInput, SymbolQuery,
    SymbolResolveRequest, SymbolSearchRequest, SymbolShowInput, UntrustedSymbolSelector,
};
pub(crate) use domain::{
    ExternalFailureId, PlanId, RecoveryId, SymbolSelector, VerifiedAddDeclarationPlanId,
    VerifiedAddFilePlanId, VerifiedAddFileRecoveryId, WorkspaceKotlinPath,
};
pub(crate) use protocol::OperationStatus;
pub(crate) use protocol::PUBLIC_PROTOCOL_SCHEMA_VERSION;
pub(crate) use protocol::ProtocolEnvelope;
use protocol::ProtocolFailure;
pub(crate) use registry::{
    Capability, OperationDefinition, OperationId, Paging, RequestType, operation_definitions,
};
use std::path::PathBuf;

pub(crate) fn symbol_search(workspace_root: PathBuf, query: String) -> ProtocolEnvelope {
    let query = match SymbolQuery::parse(query) {
        Ok(query) => query,
        Err(reason) => return invalid_input(OperationId::SymbolSearch, "query", reason),
    };
    execution::execute(
        workspace_root,
        PublicOperation::SymbolSearch(SymbolSearchRequest { query }),
    )
}

pub(crate) fn symbol_resolve(workspace_root: PathBuf, query: String) -> ProtocolEnvelope {
    let query = match SymbolQuery::parse(query) {
        Ok(query) => query,
        Err(reason) => return invalid_input(OperationId::SymbolResolve, "query", reason),
    };
    execution::execute(
        workspace_root,
        PublicOperation::SymbolResolve(SymbolResolveRequest { query }),
    )
}

pub(crate) fn symbol_show(workspace_root: PathBuf, selector: String) -> ProtocolEnvelope {
    let selector = match UntrustedSymbolSelector::parse(selector) {
        Ok(selector) => selector,
        Err(reason) => return invalid_input(OperationId::SymbolShow, "selector", reason),
    };
    execution::execute(
        workspace_root,
        PublicOperation::SymbolShow(SymbolShowInput { selector }),
    )
}

pub(crate) fn relation_references(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    let selector = match UntrustedSymbolSelector::parse(selector) {
        Ok(selector) => selector,
        Err(reason) => {
            return invalid_input(OperationId::RelationReferences, "selector", reason);
        }
    };
    execution::execute(
        workspace_root,
        PublicOperation::RelationReferences(RelationReferencesInput {
            selector,
            continuation,
        }),
    )
}

pub(crate) fn relation_calls_incoming(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    exact_operation(
        workspace_root,
        selector,
        continuation,
        OperationId::RelationCallsIncoming,
        PublicOperation::RelationCallsIncoming,
    )
}

pub(crate) fn relation_calls_outgoing(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    exact_operation(
        workspace_root,
        selector,
        continuation,
        OperationId::RelationCallsOutgoing,
        PublicOperation::RelationCallsOutgoing,
    )
}

pub(crate) fn relation_implementations(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    exact_operation(
        workspace_root,
        selector,
        continuation,
        OperationId::RelationImplementations,
        PublicOperation::RelationImplementations,
    )
}

pub(crate) fn relation_hierarchy_supertypes(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    exact_operation(
        workspace_root,
        selector,
        continuation,
        OperationId::RelationHierarchySupertypes,
        PublicOperation::RelationHierarchySupertypes,
    )
}

pub(crate) fn relation_hierarchy_subtypes(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    exact_operation(
        workspace_root,
        selector,
        continuation,
        OperationId::RelationHierarchySubtypes,
        PublicOperation::RelationHierarchySubtypes,
    )
}

pub(crate) fn graph_impact(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
) -> ProtocolEnvelope {
    impact::execute(workspace_root, selector, continuation)
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum MutationSelectorFamily {
    Rename,
    ReplaceDeclaration,
}

pub(crate) fn authenticate_mutation_selector(
    workspace_root: PathBuf,
    selector: String,
    family: MutationSelectorFamily,
) -> Result<SymbolSelector, Box<ProtocolEnvelope>> {
    let (operation, family) = match family {
        MutationSelectorFamily::Rename => (OperationId::ChangePlanRename, "RENAME"),
        MutationSelectorFamily::ReplaceDeclaration => {
            (OperationId::ChangePlanReplace, "REPLACE_DECLARATION")
        }
    };
    let selector = UntrustedSymbolSelector::parse(selector)
        .map_err(|reason| Box::new(invalid_input(operation, "selector", reason)))?;
    let runtime = crate::cli::AgentRuntimeArgs {
        workspace_root: Some(workspace_root),
    };
    execution::authenticate_selector(&runtime, selector, family)
        .map_err(|failure| Box::new(ProtocolEnvelope::rejected(operation, failure)))
}

fn exact_operation(
    workspace_root: PathBuf,
    selector: String,
    continuation: Option<String>,
    operation: OperationId,
    request: impl FnOnce(ExactSymbolRequest) -> PublicOperation,
) -> ProtocolEnvelope {
    let selector = match UntrustedSymbolSelector::parse(selector) {
        Ok(selector) => selector,
        Err(reason) => return invalid_input(operation, "selector", reason),
    };
    execution::execute(
        workspace_root,
        request(ExactSymbolRequest {
            selector,
            continuation,
        }),
    )
}

fn invalid_input(operation: OperationId, field: &'static str, reason: &str) -> ProtocolEnvelope {
    ProtocolEnvelope::rejected(
        operation,
        ProtocolFailure::InvalidInput {
            field,
            reason: reason.to_string(),
        },
    )
}
