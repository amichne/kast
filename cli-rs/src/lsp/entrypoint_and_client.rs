pub fn run(args: LspArgs) -> Result<i32> {
    if !args.stdio {
        return Err(CliError::new(
            "CLI_USAGE",
            "lsp currently supports stdio only; run `kast agent lsp --stdio`",
        ));
    }
    let client = RuntimeKastRpc::new(args);
    let mut server = LspServer::new(client);
    let stdin = io::stdin();
    let stdout = io::stdout();
    server.serve(stdin.lock(), stdout.lock())?;
    Ok(0)
}

trait KastRpcClient {
    fn initial_workspace_root(&self) -> Option<PathBuf>;
    fn set_workspace_root(&mut self, workspace_root: PathBuf);
    fn capabilities(&mut self) -> Result<Value>;
    fn request(&mut self, method: &str, params: Value) -> Result<Value>;
}

struct RuntimeKastRpc {
    workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
    request_timeout_ms: u64,
}

impl RuntimeKastRpc {
    fn new(args: LspArgs) -> Self {
        Self {
            workspace_root: args.workspace_root,
            backend_name: args.backend_name,
            request_timeout_ms: args.request_timeout_ms,
        }
    }

    fn runtime_args(&self) -> Result<RuntimeArgs> {
        let workspace_root = self.workspace_root.clone().ok_or_else(|| {
            CliError::new(
                "LSP_WORKSPACE_UNINITIALIZED",
                "LSP workspace root has not been initialized",
            )
        })?;
        Ok(RuntimeArgs {
            workspace_root: Some(workspace_root),
            backend_name: self.backend_name,
            idea_home: None,
            wait_timeout_ms: self.request_timeout_ms,
            accept_indexing: Some(false),
            no_auto_start: None,
            socket_path: None,
            module_name: None,
            source_roots: None,
            classpath: None,
            request_timeout_ms: None,
            max_results: None,
            max_concurrent_requests: None,
            profile: false,
            profile_modes: None,
            profile_duration: None,
            profile_otlp_endpoint: None,
        })
    }

    fn direct_rpc_request(&self, method: &str, params: Value) -> Result<Value> {
        let raw_request = serde_json::to_string(&json!({
            "jsonrpc": JSONRPC_VERSION,
            "method": method,
            "params": params,
            "id": 1
        }))?;
        let workspace_root = self.workspace_root.clone();
        let response = match method {
            "database/metrics" => crate::metrics::try_handle_raw_rpc(&raw_request, workspace_root)?,
            "symbol/query" => {
                crate::symbol_query::try_handle_raw_rpc(&raw_request, workspace_root)?
            }
            _ => unreachable!("direct RPC routing is limited to Rust-owned methods"),
        };
        let response = response.ok_or_else(|| {
            CliError::new(
                "DIRECT_RPC_UNHANDLED",
                format!("Rust-owned RPC method `{method}` was not handled"),
            )
        })?;
        json_rpc_result_from_response(&response)
    }
}

impl KastRpcClient for RuntimeKastRpc {
    fn initial_workspace_root(&self) -> Option<PathBuf> {
        self.workspace_root.clone()
    }

    fn set_workspace_root(&mut self, workspace_root: PathBuf) {
        self.workspace_root = Some(workspace_root);
    }

    fn capabilities(&mut self) -> Result<Value> {
        runtime::capabilities(self.runtime_args()?)
    }

    fn request(&mut self, method: &str, params: Value) -> Result<Value> {
        if matches!(method, "database/metrics" | "symbol/query") {
            return self.direct_rpc_request(method, params);
        }
        let ensure = runtime::workspace_ensure(self.runtime_args()?)?;
        rpc::request(
            Path::new(&ensure.selected.descriptor.socket_path),
            method,
            params,
        )
    }
}
