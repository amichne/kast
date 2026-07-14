use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde_json::{Value, json};
use thiserror::Error;

use super::model::{
    BackendModuleCoverage, BackendModuleInventory, BackendModuleName, BackendWorkspaceCoverage,
    BackendWorkspaceInventory, BackendWorkspacePageToken, BackendWorkspaceSnapshotToken,
    WorkspaceContainedRoot, WorkspaceFilePath, WorkspaceInventoryLimitationCode,
    WorkspaceLaneStamp, WorkspaceLaneUnavailableReason, WorkspaceRequestedKindDomain,
    WorkspaceRoot,
};

const PAGE_SIZE: usize = 200;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub(crate) enum BackendRpcFailure {
    #[error("backend API error `{code}`: {message}")]
    Api {
        code: String,
        message: String,
        reason: Option<String>,
    },
    #[error("backend transport failed: {0}")]
    Transport(String),
    #[error("backend returned invalid workspace inventory: {0}")]
    InvalidResponse(String),
    #[error("backend workspace path `{path}` cannot be proven contained: {reason}")]
    Containment { path: PathBuf, reason: String },
}

pub(crate) trait BackendWorkspaceRpc {
    fn request(&mut self, request: Value) -> Result<Value, BackendRpcFailure>;
}

pub(crate) struct RawRpcWorkspaceBackend<'a> {
    session: &'a crate::runtime::RawRpcSession,
    workspace_root: PathBuf,
}

impl<'a> RawRpcWorkspaceBackend<'a> {
    pub(crate) fn new(
        session: &'a crate::runtime::RawRpcSession,
        workspace_root: &WorkspaceRoot,
    ) -> Self {
        Self {
            session,
            workspace_root: workspace_root.as_path().to_path_buf(),
        }
    }
}

impl BackendWorkspaceRpc for RawRpcWorkspaceBackend<'_> {
    fn request(&mut self, request: Value) -> Result<Value, BackendRpcFailure> {
        let encoded = serde_json::to_string(&request)
            .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
        let raw = crate::runtime::raw_request_passthrough_in_session(
            encoded,
            Some(self.workspace_root.clone()),
            self.session,
        )
        .map_err(|error| BackendRpcFailure::Transport(error.to_string()))?;
        decode_rpc_response(&raw)
    }
}

fn decode_rpc_response(raw: &str) -> Result<Value, BackendRpcFailure> {
    let response: Value = serde_json::from_str(raw)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    if let Some(error) = response.get("error") {
        let data = error.get("data");
        let code = data
            .and_then(|value| value.get("code"))
            .or_else(|| error.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("RPC_ERROR")
            .to_string();
        let message = data
            .and_then(|value| value.get("message"))
            .or_else(|| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("JSON-RPC request failed")
            .to_string();
        let reason = data
            .and_then(|value| value.get("details"))
            .and_then(|value| value.get("reason"))
            .and_then(Value::as_str)
            .map(str::to_string);
        return Err(BackendRpcFailure::Api {
            code,
            message,
            reason,
        });
    }
    response
        .get("result")
        .cloned()
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("missing result".to_string()))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceInventoryResponse {
    snapshot_token: String,
    #[serde(default)]
    modules: Vec<WorkspaceModuleResponse>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceModuleResponse {
    name: String,
    source_roots: Vec<PathBuf>,
    content_roots: Vec<PathBuf>,
    dependency_module_names: Vec<String>,
    #[serde(default)]
    files: Vec<PathBuf>,
    returned_file_count: usize,
    files_truncated: bool,
    file_count: usize,
    #[serde(default)]
    next_page_token: Option<String>,
}

#[derive(Debug)]
struct MetadataModule {
    name: BackendModuleName,
    raw_source_roots: Vec<PathBuf>,
    source_roots: BTreeSet<WorkspaceContainedRoot>,
    raw_content_roots: Vec<PathBuf>,
    content_roots: BTreeSet<WorkspaceContainedRoot>,
    dependency_module_names: BTreeSet<BackendModuleName>,
    file_count: usize,
    containment_complete: bool,
}

#[derive(Debug)]
struct BackendAttempt {
    snapshot: BackendWorkspaceSnapshotToken,
    modules: Vec<MetadataModule>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BackendFailureScope {
    Metadata,
    WholeAttempt,
}

#[derive(Debug)]
struct BackendAttemptFailure {
    failure: BackendRpcFailure,
    scope: BackendFailureScope,
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
}

pub(super) fn collect_backend_inventory(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> BackendWorkspaceInventory {
    let first = collect_attempt(root, kind_domain, rpc);
    match first {
        Err(failure) if is_stale(&failure.failure) => match collect_attempt(root, kind_domain, rpc)
        {
            Ok(inventory) => inventory,
            Err(second) if is_stale(&second.failure) => stale_inventory(second.modules),
            Err(second) => failure_inventory(second),
        },
        Ok(inventory) => inventory,
        Err(failure) => failure_inventory(failure),
    }
}

pub(super) fn revalidate_backend_inventory(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    before: &BackendWorkspaceInventory,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> WorkspaceLaneStamp<super::model::BackendWorkspaceStamp> {
    if let Some(snapshot) = before.snapshot_token() {
        return match validate_snapshot(kind_domain, snapshot, rpc) {
            Ok(()) => before.stamp().map_or_else(
                || {
                    WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(
                        "BACKEND_LEASE_STAMP_UNAVAILABLE",
                    ))
                },
                WorkspaceLaneStamp::Available,
            ),
            Err(failure) => WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(
                format!("BACKEND_LEASE_REVALIDATION:{failure:?}"),
            )),
        };
    }

    backend_inventory_barrier_stamp(&collect_backend_inventory(root, kind_domain, rpc))
}

fn backend_inventory_barrier_stamp(
    inventory: &BackendWorkspaceInventory,
) -> WorkspaceLaneStamp<super::model::BackendWorkspaceStamp> {
    inventory.stamp().map_or_else(
        || {
            WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
                "BACKEND_{:?}:{:?}",
                inventory.coverage(),
                inventory.limitations()
            )))
        },
        WorkspaceLaneStamp::Available,
    )
}

fn collect_attempt(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BackendWorkspaceInventory, BackendAttemptFailure> {
    let metadata =
        fetch_metadata(root, kind_domain, rpc).map_err(|failure| BackendAttemptFailure {
            failure,
            scope: BackendFailureScope::Metadata,
            modules: BTreeMap::new(),
        })?;
    let mut files = BTreeMap::<WorkspaceFilePath, BTreeSet<BackendModuleName>>::new();
    let mut modules = BTreeMap::new();
    let mut limitations = BTreeMap::new();
    let mut workspace_coverage = BackendWorkspaceCoverage::Complete;

    for module in &metadata.modules {
        if !module.containment_complete {
            workspace_coverage = BackendWorkspaceCoverage::Partial;
            increment(
                &mut limitations,
                WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
            );
        }
        match exhaust_module(root, kind_domain, &metadata.snapshot, module, rpc) {
            Ok(module_files) => {
                for path in module_files {
                    files.entry(path).or_default().insert(module.name.clone());
                }
                modules.insert(
                    module.name.clone(),
                    module_inventory(
                        module,
                        if module.containment_complete {
                            BackendModuleCoverage::Complete
                        } else {
                            BackendModuleCoverage::Partial
                        },
                    ),
                );
            }
            Err(failure) if is_project_model_incomplete(&failure) || is_stale(&failure) => {
                return Err(BackendAttemptFailure {
                    failure,
                    scope: BackendFailureScope::WholeAttempt,
                    modules: partial_modules(&metadata.modules),
                });
            }
            Err(failure) => {
                workspace_coverage = BackendWorkspaceCoverage::Partial;
                increment(
                    &mut limitations,
                    WorkspaceInventoryLimitationCode::BackendPageIncomplete,
                );
                if matches!(failure, BackendRpcFailure::Containment { .. }) {
                    increment(
                        &mut limitations,
                        WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
                    );
                }
                modules.insert(
                    module.name.clone(),
                    module_inventory(module, BackendModuleCoverage::Partial),
                );
            }
        }
    }

    validate_snapshot(kind_domain, &metadata.snapshot, rpc).map_err(|failure| {
        BackendAttemptFailure {
            failure,
            scope: BackendFailureScope::WholeAttempt,
            modules: partial_modules(&metadata.modules),
        }
    })?;
    Ok(BackendWorkspaceInventory::new(
        files,
        modules,
        workspace_coverage,
        Some(metadata.snapshot),
        limitations,
    ))
}

fn fetch_metadata(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BackendAttempt, BackendRpcFailure> {
    let result = rpc.request(workspace_request(json!({
        "includeFiles": false,
        "kindDomain": kind_domain_wire(kind_domain),
    })))?;
    let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    let snapshot = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string()))?;
    let mut names = BTreeSet::new();
    let mut modules = Vec::with_capacity(decoded.modules.len());
    for raw in decoded.modules {
        let name = BackendModuleName::parse(raw.name)
            .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid module name".to_string()))?;
        if !names.insert(name.clone())
            || !raw.files.is_empty()
            || raw.returned_file_count != 0
            || raw.files_truncated
            || raw.next_page_token.is_some()
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "metadata modules must be unique and contain an empty non-paged file view"
                    .to_string(),
            ));
        }
        if !strictly_sorted(&raw.source_roots)
            || !strictly_sorted(&raw.content_roots)
            || !strictly_sorted(&raw.dependency_module_names)
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "module roots and dependencies must be strictly sorted and unique".to_string(),
            ));
        }
        let raw_source_roots = raw.source_roots;
        let raw_content_roots = raw.content_roots;
        let (source_roots, source_contained) =
            normalize_roots(root.as_path(), raw_source_roots.clone());
        let (content_roots, content_contained) =
            normalize_roots(root.as_path(), raw_content_roots.clone());
        let dependency_module_names = raw
            .dependency_module_names
            .into_iter()
            .map(|dependency| {
                BackendModuleName::parse(dependency).ok_or_else(|| {
                    BackendRpcFailure::InvalidResponse("invalid dependency module name".to_string())
                })
            })
            .collect::<Result<_, _>>()?;
        modules.push(MetadataModule {
            name,
            raw_source_roots,
            source_roots,
            raw_content_roots,
            content_roots,
            dependency_module_names,
            file_count: raw.file_count,
            containment_complete: source_contained && content_contained,
        });
    }
    modules.sort_by(|left, right| left.name.cmp(&right.name));
    Ok(BackendAttempt { snapshot, modules })
}

fn exhaust_module(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    snapshot: &BackendWorkspaceSnapshotToken,
    module: &MetadataModule,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BTreeSet<WorkspaceFilePath>, BackendRpcFailure> {
    if module.file_count == 0 {
        return Ok(BTreeSet::new());
    }
    let mut token: Option<BackendWorkspacePageToken> = None;
    let mut seen_tokens = BTreeSet::new();
    let mut files = BTreeSet::new();
    loop {
        if let Some(page_token) = token.as_ref()
            && !seen_tokens.insert(page_token.clone())
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page token repeated".to_string(),
            ));
        }
        let mut params = json!({
            "includeFiles": true,
            "kindDomain": kind_domain_wire(kind_domain),
            "maxFilesPerModule": PAGE_SIZE,
            "moduleName": module.name.as_str(),
            "snapshotToken": snapshot.as_str(),
        });
        if let Some(page_token) = token.as_ref() {
            params["pageToken"] = Value::String(page_token.as_str().to_string());
        }
        let result = rpc.request(workspace_request(params))?;
        let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
            .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
        let echoed_snapshot = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
            .ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string())
            })?;
        if &echoed_snapshot != snapshot || decoded.modules.len() != 1 {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page is not bound to the requested snapshot and module".to_string(),
            ));
        }
        let page =
            decoded.modules.into_iter().next().ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("missing module page".to_string())
            })?;
        if page.name != module.name.as_str()
            || page.file_count != module.file_count
            || page.returned_file_count != page.files.len()
            || page.files_truncated != page.next_page_token.is_some()
            || !page_metadata_matches(root, &page, module)?
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page module identity, fingerprint, or cardinality changed".to_string(),
            ));
        }
        for raw_path in page.files {
            let path = contained_workspace_path(root.as_path(), &raw_path)?;
            if !files.insert(path) {
                return Err(BackendRpcFailure::InvalidResponse(
                    "workspace module pages overlap".to_string(),
                ));
            }
            if files.len() > module.file_count {
                return Err(BackendRpcFailure::InvalidResponse(
                    "workspace module returned more files than declared".to_string(),
                ));
            }
        }
        token = page
            .next_page_token
            .map(|value| {
                BackendWorkspacePageToken::parse(value).ok_or_else(|| {
                    BackendRpcFailure::InvalidResponse("invalid page token".to_string())
                })
            })
            .transpose()?;
        if token.is_some() && page.returned_file_count == 0 {
            return Err(BackendRpcFailure::InvalidResponse(
                "nonterminal workspace module pages must make progress".to_string(),
            ));
        }
        if token.is_none() {
            break;
        }
    }
    if files.len() != module.file_count {
        return Err(BackendRpcFailure::InvalidResponse(format!(
            "workspace module returned {} of {} declared files",
            files.len(),
            module.file_count
        )));
    }
    Ok(files)
}

fn validate_snapshot(
    kind_domain: WorkspaceRequestedKindDomain,
    snapshot: &BackendWorkspaceSnapshotToken,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<(), BackendRpcFailure> {
    let result = rpc.request(workspace_request(json!({
        "includeFiles": false,
        "kindDomain": kind_domain_wire(kind_domain),
        "snapshotToken": snapshot.as_str(),
    })))?;
    let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    let echoed = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string()))?;
    if &echoed != snapshot {
        return Err(BackendRpcFailure::InvalidResponse(
            "snapshot validation returned another snapshot".to_string(),
        ));
    }
    Ok(())
}

fn stale_inventory(
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
) -> BackendWorkspaceInventory {
    let mut limitations = BTreeMap::new();
    increment(
        &mut limitations,
        WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale,
    );
    BackendWorkspaceInventory::new(
        BTreeMap::new(),
        modules,
        BackendWorkspaceCoverage::Partial,
        None,
        limitations,
    )
}

fn failure_inventory(attempt_failure: BackendAttemptFailure) -> BackendWorkspaceInventory {
    let BackendAttemptFailure {
        failure,
        scope,
        modules,
    } = attempt_failure;
    let mut limitations = BTreeMap::new();
    let (coverage, limitation) = if is_project_model_incomplete(&failure) {
        let coverage = match scope {
            BackendFailureScope::Metadata => BackendWorkspaceCoverage::Unavailable,
            BackendFailureScope::WholeAttempt => BackendWorkspaceCoverage::Partial,
        };
        (coverage, project_model_limitation(&failure))
    } else {
        let (coverage, limitation) = match scope {
            BackendFailureScope::Metadata => (
                BackendWorkspaceCoverage::Unavailable,
                WorkspaceInventoryLimitationCode::BackendMetadataUnavailable,
            ),
            BackendFailureScope::WholeAttempt => (
                BackendWorkspaceCoverage::Partial,
                WorkspaceInventoryLimitationCode::BackendPageIncomplete,
            ),
        };
        (coverage, limitation)
    };
    increment(&mut limitations, limitation);
    BackendWorkspaceInventory::new(BTreeMap::new(), modules, coverage, None, limitations)
}

fn partial_modules(
    metadata: &[MetadataModule],
) -> BTreeMap<BackendModuleName, BackendModuleInventory> {
    metadata
        .iter()
        .map(|module| {
            (
                module.name.clone(),
                module_inventory(module, BackendModuleCoverage::Partial),
            )
        })
        .collect()
}

fn project_model_limitation(failure: &BackendRpcFailure) -> WorkspaceInventoryLimitationCode {
    match failure {
        BackendRpcFailure::Api { reason, .. } => match reason.as_deref() {
            Some("RUNTIME_INDEXING") => WorkspaceInventoryLimitationCode::RuntimeIndexing,
            Some("LINKED_ROOT_UNASSOCIATED") => {
                WorkspaceInventoryLimitationCode::LinkedRootUnassociated
            }
            Some("PROJECT_MODEL_UNAVAILABLE") | None | Some(_) => {
                WorkspaceInventoryLimitationCode::ProjectModelUnavailable
            }
        },
        BackendRpcFailure::Transport(_)
        | BackendRpcFailure::InvalidResponse(_)
        | BackendRpcFailure::Containment { .. } => {
            WorkspaceInventoryLimitationCode::ProjectModelUnavailable
        }
    }
}

fn is_stale(failure: &BackendRpcFailure) -> bool {
    matches!(failure, BackendRpcFailure::Api { code, .. } if code == "STALE_WORKSPACE_INVENTORY")
}

fn is_project_model_incomplete(failure: &BackendRpcFailure) -> bool {
    matches!(failure, BackendRpcFailure::Api { code, .. } if code == "WORKSPACE_PROJECT_MODEL_INCOMPLETE")
}

fn workspace_request(params: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/workspace-files",
        "params": params,
    })
}

fn kind_domain_wire(kind_domain: WorkspaceRequestedKindDomain) -> &'static str {
    match kind_domain {
        WorkspaceRequestedKindDomain::SourceOnly => "SOURCE_ONLY",
        WorkspaceRequestedKindDomain::ScriptOnly => "SCRIPT_ONLY",
        WorkspaceRequestedKindDomain::Mixed => "MIXED",
    }
}

fn contained_workspace_path(
    root: &Path,
    path: &Path,
) -> Result<WorkspaceFilePath, BackendRpcFailure> {
    let relative = if path.is_absolute() {
        path.strip_prefix(root)
            .map_err(|_| BackendRpcFailure::Containment {
                path: path.to_path_buf(),
                reason: "absolute path is outside the admitted workspace".to_string(),
            })?
            .to_path_buf()
    } else {
        path.to_path_buf()
    };
    let relative = WorkspaceFilePath::from_relative_path(relative).ok_or_else(|| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason: "path is not normalized workspace-relative".to_string(),
        }
    })?;
    prove_containment(root, relative.as_path()).map_err(|reason| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason,
        }
    })?;
    Ok(relative)
}

fn prove_containment(root: &Path, relative: &Path) -> Result<(), String> {
    let candidate = root.join(relative);
    if std::fs::symlink_metadata(&candidate).is_ok() {
        let canonical = std::fs::canonicalize(&candidate)
            .map_err(|error| format!("existing path cannot be canonicalized: {error}"))?;
        return canonical
            .starts_with(root)
            .then_some(())
            .ok_or_else(|| "existing path resolves outside the admitted workspace".to_string());
    }
    let mut ancestor = candidate.as_path();
    while std::fs::symlink_metadata(ancestor).is_err() {
        ancestor = ancestor
            .parent()
            .ok_or_else(|| "missing path has no existing ancestor".to_string())?;
    }
    let canonical = std::fs::canonicalize(ancestor)
        .map_err(|error| format!("deepest existing ancestor cannot be canonicalized: {error}"))?;
    canonical
        .starts_with(root)
        .then_some(())
        .ok_or_else(|| "deepest existing ancestor resolves outside the workspace".to_string())
}

fn normalize_roots(
    root: &Path,
    raw_roots: Vec<PathBuf>,
) -> (BTreeSet<WorkspaceContainedRoot>, bool) {
    let mut contained = true;
    let roots = raw_roots
        .into_iter()
        .filter_map(|raw| match contained_workspace_root(root, &raw) {
            Ok(path) => Some(path),
            Err(_) => {
                contained = false;
                None
            }
        })
        .collect();
    (roots, contained)
}

fn contained_workspace_root(
    root: &Path,
    path: &Path,
) -> Result<WorkspaceContainedRoot, BackendRpcFailure> {
    let relative = if path.is_absolute() {
        path.strip_prefix(root)
            .map_err(|_| BackendRpcFailure::Containment {
                path: path.to_path_buf(),
                reason: "absolute root is outside the admitted workspace".to_string(),
            })?
            .to_path_buf()
    } else {
        path.to_path_buf()
    };
    let relative = WorkspaceContainedRoot::from_relative_path(relative).ok_or_else(|| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason: "root is not normalized workspace-relative".to_string(),
        }
    })?;
    prove_containment(root, relative.as_path()).map_err(|reason| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason,
        }
    })?;
    Ok(relative)
}

fn page_metadata_matches(
    root: &WorkspaceRoot,
    page: &WorkspaceModuleResponse,
    module: &MetadataModule,
) -> Result<bool, BackendRpcFailure> {
    if !strictly_sorted(&page.source_roots)
        || !strictly_sorted(&page.content_roots)
        || !strictly_sorted(&page.dependency_module_names)
    {
        return Ok(false);
    }
    let (source_roots, source_contained) =
        normalize_roots(root.as_path(), page.source_roots.clone());
    let (content_roots, content_contained) =
        normalize_roots(root.as_path(), page.content_roots.clone());
    let dependencies = page
        .dependency_module_names
        .iter()
        .cloned()
        .map(|name| {
            BackendModuleName::parse(name).ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("invalid dependency module name".to_string())
            })
        })
        .collect::<Result<BTreeSet<_>, _>>()?;
    let page_containment_complete = source_contained && content_contained;
    Ok(page.source_roots == module.raw_source_roots
        && page.content_roots == module.raw_content_roots
        && source_roots == module.source_roots
        && content_roots == module.content_roots
        && dependencies == module.dependency_module_names
        && page_containment_complete == module.containment_complete)
}

fn module_inventory(
    module: &MetadataModule,
    coverage: BackendModuleCoverage,
) -> BackendModuleInventory {
    BackendModuleInventory::new(
        module.name.clone(),
        module.source_roots.clone(),
        module.content_roots.clone(),
        module.dependency_module_names.clone(),
        module.file_count,
        coverage,
    )
}

fn strictly_sorted<T: Ord>(values: &[T]) -> bool {
    values.windows(2).all(|window| window[0] < window[1])
}

fn increment(
    limitations: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    code: WorkspaceInventoryLimitationCode,
) {
    limitations
        .entry(code)
        .and_modify(|count| *count += 1)
        .or_insert(1);
}

#[cfg(test)]
mod rpc_error_tests {
    use super::*;

    #[test]
    fn project_model_reason_is_decoded_from_the_typed_error_details_envelope() {
        for (reason, expected) in [
            (
                "RUNTIME_INDEXING",
                WorkspaceInventoryLimitationCode::RuntimeIndexing,
            ),
            (
                "LINKED_ROOT_UNASSOCIATED",
                WorkspaceInventoryLimitationCode::LinkedRootUnassociated,
            ),
        ] {
            let raw = serde_json::json!({
                "jsonrpc": "2.0",
                "id": 1,
                "error": {
                    "code": -32000,
                    "message": "Project model incomplete",
                    "data": {
                        "code": "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
                        "message": "Project model incomplete",
                        "details": {"reason": reason}
                    }
                }
            })
            .to_string();
            let failure = decode_rpc_response(&raw).expect_err("typed RPC error");

            assert_eq!(project_model_limitation(&failure), expected, "{failure:?}");
        }

        let misplaced = serde_json::json!({
            "jsonrpc": "2.0",
            "id": 1,
            "error": {
                "code": -32000,
                "message": "Project model incomplete",
                "data": {
                    "code": "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
                    "message": "Project model incomplete",
                    "reason": "RUNTIME_INDEXING"
                }
            }
        })
        .to_string();
        let failure = decode_rpc_response(&misplaced).expect_err("typed RPC error");
        assert_eq!(
            project_model_limitation(&failure),
            WorkspaceInventoryLimitationCode::ProjectModelUnavailable
        );
    }
}
