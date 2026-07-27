struct ScriptedWorkspaceBackend {
    responses: VecDeque<Result<serde_json::Value, super::backend::BackendRpcFailure>>,
    requests: Vec<serde_json::Value>,
}

impl ScriptedWorkspaceBackend {
    fn new(responses: Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>>) -> Self {
        Self {
            responses: responses.into(),
            requests: Vec::new(),
        }
    }
}

impl super::backend::BackendWorkspaceRpc for ScriptedWorkspaceBackend {
    fn request(
        &mut self,
        request: serde_json::Value,
    ) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
        self.requests.push(request);
        self.responses
            .pop_front()
            .expect("scripted workspace backend response")
    }
}

fn backend_result(
    snapshot: &str,
    modules: Vec<serde_json::Value>,
) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
    Ok(serde_json::json!({
        "snapshotToken": snapshot,
        "modules": modules,
        "schemaVersion": 5
    }))
}

fn backend_module(
    name: &str,
    count: usize,
    files: &[&str],
    next: Option<&str>,
) -> serde_json::Value {
    backend_module_with_ownership(name, count, files, next, &[], &[], &[])
}

fn backend_module_with_ownership(
    name: &str,
    count: usize,
    files: &[&str],
    next: Option<&str>,
    source_roots: &[&str],
    content_roots: &[&str],
    dependencies: &[&str],
) -> serde_json::Value {
    serde_json::json!({
        "name": name,
        "sourceRoots": source_roots,
        "contentRoots": content_roots,
        "dependencyModuleNames": dependencies,
        "files": files,
        "returnedFileCount": files.len(),
        "filesTruncated": next.is_some(),
        "fileCount": count,
        "nextPageToken": next,
    })
}
