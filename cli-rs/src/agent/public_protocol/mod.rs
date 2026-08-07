mod backend;
mod domain;
mod execution;
mod protocol;

use domain::{
    PublicOperation, RelationReferencesInput, SymbolQuery, SymbolResolveRequest,
    SymbolSearchRequest, SymbolShowInput, UntrustedSymbolSelector,
};
pub(crate) use protocol::ProtocolEnvelope;
use protocol::{OperationId, ProtocolFailure};
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

fn invalid_input(operation: OperationId, field: &'static str, reason: &str) -> ProtocolEnvelope {
    ProtocolEnvelope::rejected(
        operation,
        ProtocolFailure::InvalidInput {
            field,
            reason: reason.to_string(),
        },
    )
}
