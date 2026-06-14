use crate::cli::{BackendName, LspArgs, RuntimeArgs};
use crate::config;
use crate::error::{CliError, Result};
use crate::{rpc, runtime};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};

const JSONRPC_VERSION: &str = "2.0";
const MAX_LSP_RESULTS: usize = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KastCustomLspRoute {
    lsp_method: &'static str,
    rpc_method: &'static str,
    inject_workspace_root: bool,
}

include!(concat!(env!("OUT_DIR"), "/lsp_custom_routes.rs"));

pub fn run(args: LspArgs) -> Result<i32> {
    if !args.stdio {
        return Err(CliError::new(
            "CLI_USAGE",
            "lsp currently supports stdio only; run `kast lsp --stdio`",
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
            install_version: None,
            install_base_url: None,
            install_insecure_skip_tls_verify: false,
            auto_install_headless: false,
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

#[derive(Debug, Clone)]
struct OpenDocument {
    text: String,
    dirty: bool,
}

struct LspServer<C: KastRpcClient> {
    rpc: C,
    workspace_root: Option<PathBuf>,
    backend_capabilities: Option<Value>,
    documents: HashMap<PathBuf, OpenDocument>,
    prepared_renames: HashSet<String>,
    shutdown_requested: bool,
    exited: bool,
}

impl<C: KastRpcClient> LspServer<C> {
    fn new(rpc: C) -> Self {
        Self {
            rpc,
            workspace_root: None,
            backend_capabilities: None,
            documents: HashMap::new(),
            prepared_renames: HashSet::new(),
            shutdown_requested: false,
            exited: false,
        }
    }

    fn serve<R: BufRead, W: Write>(&mut self, mut reader: R, mut writer: W) -> Result<()> {
        while let Some(message) = read_message(&mut reader)? {
            if let Some(response) = self.handle_message(message) {
                write_message(&mut writer, &response)?;
            }
            if self.exited {
                break;
            }
        }
        Ok(())
    }

    fn handle_message(&mut self, message: Value) -> Option<Value> {
        let id = message.get("id").cloned();
        let method = message.get("method").and_then(Value::as_str);
        let params = message.get("params").cloned().unwrap_or_else(|| json!({}));
        let Some(method) = method else {
            return id.map(|id| error_response(id, LspError::invalid_request("missing method")));
        };

        if id.is_none() {
            if let Err(error) = self.handle_notification(method, params) {
                eprintln!("kast lsp notification error: {}", error.message);
            }
            return None;
        }

        let id = id.expect("request id is present");
        match self.handle_request(method, params) {
            Ok(result) => Some(success_response(id, result)),
            Err(error) => Some(error_response(id, error)),
        }
    }

    fn handle_notification(&mut self, method: &str, params: Value) -> LspResult<()> {
        match method {
            "initialized" => Ok(()),
            "exit" => {
                self.exited = true;
                Ok(())
            }
            "textDocument/didOpen" => self.did_open(params),
            "textDocument/didChange" => self.did_change(params),
            "textDocument/didClose" => self.did_close(params),
            _ => Ok(()),
        }
    }

    fn handle_request(&mut self, method: &str, params: Value) -> LspResult<Value> {
        match method {
            "initialize" => self.initialize(params),
            "shutdown" => {
                self.shutdown_requested = true;
                Ok(Value::Null)
            }
            "textDocument/definition" => self.definition(params),
            "textDocument/references" => self.references(params),
            "textDocument/hover" => self.hover(params),
            "textDocument/documentSymbol" => self.document_symbol(params),
            "workspace/symbol" => self.workspace_symbol(params),
            "textDocument/implementation" => self.implementation(params),
            "textDocument/prepareCallHierarchy" => self.prepare_call_hierarchy(params),
            "callHierarchy/incomingCalls" => self.call_hierarchy(params, "INCOMING"),
            "callHierarchy/outgoingCalls" => self.call_hierarchy(params, "OUTGOING"),
            "textDocument/prepareTypeHierarchy" => self.prepare_type_hierarchy(params),
            "typeHierarchy/supertypes" => self.type_hierarchy(params, "SUPERTYPES"),
            "typeHierarchy/subtypes" => self.type_hierarchy(params, "SUBTYPES"),
            "textDocument/prepareRename" => self.prepare_rename(params),
            "textDocument/rename" => self.rename(params),
            _ => match custom_lsp_route(method) {
                Some(route) => self.kast_custom_passthrough(route, params),
                None => Err(LspError::method_not_found(format!(
                    "unsupported LSP method `{method}`"
                ))),
            },
        }
    }

    fn initialize(&mut self, params: Value) -> LspResult<Value> {
        let initialization_options = initialization_options(&params)?;
        let workspace_root = match self.rpc.initial_workspace_root() {
            Some(root) => root,
            None => root_from_initialize_params(&params)?
                .unwrap_or(std::env::current_dir().map_err(CliError::from)?),
        };
        let workspace_root = config::normalize(workspace_root);
        self.rpc.set_workspace_root(workspace_root.clone());
        self.workspace_root = Some(workspace_root);
        let capabilities = self.rpc.capabilities().map_err(LspError::from)?;
        if initialization_options.fail_on_stale_index {
            let runtime_status = self.rpc_request("runtime/status", json!({}))?;
            reject_stale_runtime(&runtime_status)?;
        }
        let server_capabilities = server_capabilities(&capabilities);
        self.backend_capabilities = Some(capabilities);
        Ok(json!({
            "capabilities": server_capabilities,
            "serverInfo": {
                "name": "kast-lsp",
                "version": crate::cli::version()
            }
        }))
    }

    fn did_open(&mut self, params: Value) -> LspResult<()> {
        let document = params
            .get("textDocument")
            .ok_or_else(|| LspError::invalid_params("didOpen missing textDocument"))?;
        let uri = string_field(document, "uri")?;
        let text = string_field(document, "text")?.to_string();
        let path = self.path_from_uri(uri)?;
        let dirty = fs::read_to_string(&path)
            .map(|disk| disk != text)
            .unwrap_or(true);
        self.documents.insert(path, OpenDocument { text, dirty });
        Ok(())
    }

    fn did_change(&mut self, params: Value) -> LspResult<()> {
        let document = params
            .get("textDocument")
            .ok_or_else(|| LspError::invalid_params("didChange missing textDocument"))?;
        let uri = string_field(document, "uri")?;
        let path = self.path_from_uri(uri)?;
        let changes = params
            .get("contentChanges")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::invalid_params("didChange missing contentChanges"))?;
        let text = changes
            .last()
            .and_then(|change| change.get("text"))
            .and_then(Value::as_str)
            .ok_or_else(|| LspError::invalid_params("didChange requires full-sync text"))?
            .to_string();
        self.documents
            .insert(path, OpenDocument { text, dirty: true });
        self.prepared_renames.clear();
        Ok(())
    }

    fn did_close(&mut self, params: Value) -> LspResult<()> {
        let document = params
            .get("textDocument")
            .ok_or_else(|| LspError::invalid_params("didClose missing textDocument"))?;
        let uri = string_field(document, "uri")?;
        let path = self.path_from_uri(uri)?;
        self.documents.remove(&path);
        self.prepared_renames.clear();
        Ok(())
    }

    fn definition(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let result = self.rpc_request("raw/resolve", json!({ "position": position }))?;
        let symbol = result
            .get("symbol")
            .ok_or_else(|| LspError::backend_contract("raw/resolve missing symbol"))?;
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("resolved symbol missing location"))?;
        self.location_value(location)
    }

    fn references(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let include_declaration = params
            .get("context")
            .and_then(|context| context.get("includeDeclaration"))
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let result = self.rpc_request(
            "raw/references",
            json!({
                "position": position,
                "includeDeclaration": include_declaration
            }),
        )?;
        let locations = result
            .get("references")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("raw/references missing references"))?
            .clone();
        self.locations_value(&locations)
    }

    fn hover(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let result = self.rpc_request(
            "raw/resolve",
            json!({
                "position": position,
                "includeDocumentation": true
            }),
        )?;
        let symbol = result
            .get("symbol")
            .ok_or_else(|| LspError::backend_contract("raw/resolve missing symbol"))?;
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("resolved symbol missing location"))?;
        let range = self.range_value(location)?;
        Ok(json!({
            "contents": {
                "kind": "markdown",
                "value": hover_markdown(symbol)
            },
            "range": range
        }))
    }

    fn document_symbol(&mut self, params: Value) -> LspResult<Value> {
        let document = params
            .get("textDocument")
            .ok_or_else(|| LspError::invalid_params("documentSymbol missing textDocument"))?;
        let uri = string_field(document, "uri")?;
        let path = self.path_from_uri(uri)?;
        self.reject_dirty(&path)?;
        let result = self.rpc_request(
            "raw/file-outline",
            json!({
                "filePath": path.display().to_string()
            }),
        )?;
        let symbols = result
            .get("symbols")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("raw/file-outline missing symbols"))?
            .clone();
        let mut mapped = Vec::with_capacity(symbols.len());
        for symbol in &symbols {
            mapped.push(self.document_symbol_value(symbol)?);
        }
        Ok(Value::Array(mapped))
    }

    fn workspace_symbol(&mut self, params: Value) -> LspResult<Value> {
        let query = params.get("query").and_then(Value::as_str).unwrap_or("");
        let result = self.rpc_request(
            "raw/workspace-symbol",
            json!({
                "pattern": query,
                "maxResults": MAX_LSP_RESULTS
            }),
        )?;
        let symbols = result
            .get("symbols")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("raw/workspace-symbol missing symbols"))?
            .clone();
        let mut mapped = Vec::with_capacity(symbols.len());
        for symbol in symbols.iter().take(MAX_LSP_RESULTS) {
            mapped.push(self.workspace_symbol_value(symbol)?);
        }
        Ok(Value::Array(mapped))
    }

    fn implementation(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let result = self.rpc_request(
            "raw/implementations",
            json!({
                "position": position,
                "maxResults": MAX_LSP_RESULTS
            }),
        )?;
        let implementations = result
            .get("implementations")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                LspError::backend_contract("raw/implementations missing implementations")
            })?
            .clone();
        let mut mapped = Vec::with_capacity(implementations.len());
        for symbol in implementations.iter().take(MAX_LSP_RESULTS) {
            let location = symbol
                .get("location")
                .ok_or_else(|| LspError::backend_contract("implementation missing location"))?;
            mapped.push(self.location_value(location)?);
        }
        Ok(Value::Array(mapped))
    }

    fn prepare_call_hierarchy(&mut self, params: Value) -> LspResult<Value> {
        let symbol = self.resolve_symbol(params)?;
        Ok(Value::Array(vec![self.call_hierarchy_item(&symbol)?]))
    }

    fn call_hierarchy(&mut self, params: Value, direction: &str) -> LspResult<Value> {
        let item = params
            .get("item")
            .ok_or_else(|| LspError::invalid_params("call hierarchy request missing item"))?;
        let target = hierarchy_target_from_item(item)?;
        let target_path = self.backend_path(&target.file_path)?;
        let result = self.rpc_request(
            "raw/call-hierarchy",
            json!({
                "position": {
                    "filePath": target_path.display().to_string(),
                    "offset": target.offset
                },
                "direction": direction,
                "depth": 1,
                "maxTotalCalls": MAX_LSP_RESULTS,
                "maxChildrenPerNode": MAX_LSP_RESULTS
            }),
        )?;
        let root = result
            .get("root")
            .ok_or_else(|| LspError::backend_contract("raw/call-hierarchy missing root"))?;
        let children = root
            .get("children")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("call hierarchy root missing children"))?
            .clone();
        let mut mapped = Vec::with_capacity(children.len());
        for child in children.iter().take(MAX_LSP_RESULTS) {
            let symbol = child
                .get("symbol")
                .ok_or_else(|| LspError::backend_contract("call hierarchy child missing symbol"))?;
            let call_site = child.get("callSite");
            let call_range = match call_site {
                Some(Value::Object(_)) => self.range_value(call_site.expect("call site exists"))?,
                _ => self.range_value(symbol.get("location").ok_or_else(|| {
                    LspError::backend_contract("call hierarchy child missing symbol location")
                })?)?,
            };
            let item = self.call_hierarchy_item(symbol)?;
            mapped.push(if direction == "INCOMING" {
                json!({
                    "from": item,
                    "fromRanges": [call_range]
                })
            } else {
                json!({
                    "to": item,
                    "fromRanges": [call_range]
                })
            });
        }
        Ok(Value::Array(mapped))
    }

    fn prepare_type_hierarchy(&mut self, params: Value) -> LspResult<Value> {
        let symbol = self.resolve_symbol(params)?;
        Ok(Value::Array(vec![self.type_hierarchy_item(&symbol)?]))
    }

    fn type_hierarchy(&mut self, params: Value, direction: &str) -> LspResult<Value> {
        let item = params
            .get("item")
            .ok_or_else(|| LspError::invalid_params("type hierarchy request missing item"))?;
        let target = hierarchy_target_from_item(item)?;
        let target_path = self.backend_path(&target.file_path)?;
        let result = self.rpc_request(
            "raw/type-hierarchy",
            json!({
                "position": {
                    "filePath": target_path.display().to_string(),
                    "offset": target.offset
                },
                "direction": direction,
                "depth": 1,
                "maxResults": MAX_LSP_RESULTS
            }),
        )?;
        let root = result
            .get("root")
            .ok_or_else(|| LspError::backend_contract("raw/type-hierarchy missing root"))?;
        let children = root
            .get("children")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("type hierarchy root missing children"))?
            .clone();
        let mut mapped = Vec::with_capacity(children.len());
        for child in children.iter().take(MAX_LSP_RESULTS) {
            let symbol = child
                .get("symbol")
                .ok_or_else(|| LspError::backend_contract("type hierarchy child missing symbol"))?;
            mapped.push(self.type_hierarchy_item(symbol)?);
        }
        Ok(Value::Array(mapped))
    }

    fn prepare_rename(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let result = self.rpc_request("raw/resolve", json!({ "position": position.clone() }))?;
        let symbol = result
            .get("symbol")
            .ok_or_else(|| LspError::backend_contract("raw/resolve missing symbol"))?;
        validate_rename_symbol(symbol)?;
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("resolved symbol missing location"))?;
        let path = self.backend_path(string_field(location, "filePath")?)?;
        if is_generated_path(&path) {
            return Err(LspError::server_error(
                "LSP_RENAME_GENERATED_PATH",
                "rename is not allowed for generated or build output paths",
            ));
        }
        self.prepared_renames.insert(rename_key(&position)?);
        Ok(json!({
            "range": self.range_value(location)?,
            "placeholder": symbol_name(symbol)
        }))
    }

    fn rename(&mut self, params: Value) -> LspResult<Value> {
        let new_name = string_field(&params, "newName")?;
        validate_new_name(new_name)?;
        let position = self.file_position_from_text_document_params(&params)?;
        let key = rename_key(&position)?;
        if !self.prepared_renames.contains(&key) {
            return Err(LspError::server_error(
                "LSP_RENAME_NOT_PREPARED",
                "textDocument/rename requires a successful textDocument/prepareRename for the same position",
            ));
        }
        let result = self.rpc_request(
            "raw/rename",
            json!({
                "position": position,
                "newName": new_name,
                "dryRun": true
            }),
        )?;
        reject_non_exhaustive_rename(&result)?;
        let edits = result
            .get("edits")
            .and_then(Value::as_array)
            .ok_or_else(|| LspError::backend_contract("raw/rename missing edits"))?;
        if edits.len() > MAX_LSP_RESULTS {
            return Err(LspError::server_error(
                "LSP_RENAME_TOO_LARGE",
                format!(
                    "rename produced {} edits, exceeding the LSP limit of {}",
                    edits.len(),
                    MAX_LSP_RESULTS
                ),
            ));
        }
        self.workspace_edit_value(edits)
    }

    fn resolve_symbol(&mut self, params: Value) -> LspResult<Value> {
        let position = self.file_position_from_text_document_params(&params)?;
        let result = self.rpc_request("raw/resolve", json!({ "position": position }))?;
        result
            .get("symbol")
            .cloned()
            .ok_or_else(|| LspError::backend_contract("raw/resolve missing symbol"))
    }

    fn rpc_request(&mut self, method: &str, params: Value) -> LspResult<Value> {
        self.rpc.request(method, params).map_err(LspError::from)
    }

    fn kast_passthrough(&mut self, method: &str, params: Value) -> LspResult<Value> {
        self.rpc_request(method, params)
    }

    fn kast_custom_passthrough(
        &mut self,
        route: &KastCustomLspRoute,
        params: Value,
    ) -> LspResult<Value> {
        let params = if route.inject_workspace_root {
            self.with_workspace_root(params)?
        } else {
            params
        };
        self.kast_passthrough(route.rpc_method, params)
    }

    fn with_workspace_root(&self, params: Value) -> LspResult<Value> {
        let mut params = match params {
            Value::Object(params) => params,
            Value::Null => Map::new(),
            _ => {
                return Err(LspError::invalid_params(
                    "kast symbol params must be an object",
                ));
            }
        };
        if !params.contains_key("workspaceRoot") {
            let workspace_root = self.workspace_root.as_ref().ok_or_else(|| {
                LspError::server_error("LSP_WORKSPACE_UNINITIALIZED", "workspace root is not set")
            })?;
            params.insert(
                "workspaceRoot".to_string(),
                Value::String(workspace_root.display().to_string()),
            );
        }
        Ok(Value::Object(params))
    }

    fn file_position_from_text_document_params(&self, params: &Value) -> LspResult<Value> {
        let document = params
            .get("textDocument")
            .ok_or_else(|| LspError::invalid_params("missing textDocument"))?;
        let uri = string_field(document, "uri")?;
        let path = self.path_from_uri(uri)?;
        self.reject_dirty(&path)?;
        let position = params
            .get("position")
            .ok_or_else(|| LspError::invalid_params("missing position"))?;
        let line = usize_field(position, "line")?;
        let character = usize_field(position, "character")?;
        let text = self.text_for_path(&path)?;
        let offset = offset_for_position(&text, line, character)?;
        Ok(json!({
            "filePath": path.display().to_string(),
            "offset": offset
        }))
    }

    fn path_from_uri(&self, uri: &str) -> LspResult<PathBuf> {
        let path = file_uri_to_path(uri)?;
        let path = config::normalize(path);
        let workspace_root = self.workspace_root.as_ref().ok_or_else(|| {
            LspError::server_error("LSP_WORKSPACE_UNINITIALIZED", "workspace root is not set")
        })?;
        if !path.starts_with(workspace_root) {
            return Err(LspError::invalid_params(format!(
                "path `{}` is outside workspace `{}`",
                path.display(),
                workspace_root.display()
            )));
        }
        Ok(path)
    }

    fn text_for_path(&self, path: &Path) -> LspResult<String> {
        if let Some(document) = self.documents.get(path) {
            return Ok(document.text.clone());
        }
        fs::read_to_string(path).map_err(|error| {
            LspError::server_error(
                "LSP_FILE_READ_FAILED",
                format!("failed to read `{}`: {error}", path.display()),
            )
        })
    }

    fn reject_dirty(&self, path: &Path) -> LspResult<()> {
        if self
            .documents
            .get(path)
            .is_some_and(|document| document.dirty)
        {
            return Err(LspError::server_error(
                "LSP_UNSAVED_BUFFER_UNSUPPORTED",
                "Kast LSP read operations require the buffer to match the file on disk",
            ));
        }
        Ok(())
    }

    fn location_value(&self, location: &Value) -> LspResult<Value> {
        let path = self.backend_path(string_field(location, "filePath")?)?;
        Ok(json!({
            "uri": path_to_file_uri(&path.display().to_string()),
            "range": self.range_value(location)?
        }))
    }

    fn locations_value(&self, locations: &[Value]) -> LspResult<Value> {
        let mut mapped = Vec::with_capacity(locations.len().min(MAX_LSP_RESULTS));
        for location in locations.iter().take(MAX_LSP_RESULTS) {
            mapped.push(self.location_value(location)?);
        }
        Ok(Value::Array(mapped))
    }

    fn range_value(&self, location: &Value) -> LspResult<Value> {
        let path = self.backend_path(string_field(location, "filePath")?)?;
        let start = usize_field(location, "startOffset")?;
        let end = usize_field(location, "endOffset")?;
        let text = self.text_for_path(&path)?;
        let range = range_for_offsets(&text, start, end)?;
        Ok(json!({
            "start": {
                "line": range.start_line,
                "character": range.start_character
            },
            "end": {
                "line": range.end_line,
                "character": range.end_character
            }
        }))
    }

    fn document_symbol_value(&self, outline: &Value) -> LspResult<Value> {
        let symbol = outline
            .get("symbol")
            .ok_or_else(|| LspError::backend_contract("outline symbol missing symbol"))?;
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("outline symbol missing location"))?;
        let range = self.range_value(location)?;
        let mut children = Vec::new();
        if let Some(child_values) = outline.get("children").and_then(Value::as_array) {
            for child in child_values {
                children.push(self.document_symbol_value(child)?);
            }
        }
        Ok(json!({
            "name": symbol_name(symbol),
            "detail": symbol_detail(symbol),
            "kind": symbol_kind_value(symbol),
            "range": range,
            "selectionRange": range,
            "children": children
        }))
    }

    fn workspace_symbol_value(&self, symbol: &Value) -> LspResult<Value> {
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("workspace symbol missing location"))?;
        let mut value = Map::new();
        value.insert("name".to_string(), Value::String(symbol_name(symbol)));
        value.insert("kind".to_string(), json!(symbol_kind_value(symbol)));
        value.insert("location".to_string(), self.location_value(location)?);
        if let Some(container) = symbol.get("containingDeclaration").and_then(Value::as_str) {
            value.insert(
                "containerName".to_string(),
                Value::String(container.to_string()),
            );
        }
        value.insert("data".to_string(), symbol_data(symbol)?);
        Ok(Value::Object(value))
    }

    fn call_hierarchy_item(&self, symbol: &Value) -> LspResult<Value> {
        self.hierarchy_item(symbol)
    }

    fn type_hierarchy_item(&self, symbol: &Value) -> LspResult<Value> {
        self.hierarchy_item(symbol)
    }

    fn hierarchy_item(&self, symbol: &Value) -> LspResult<Value> {
        let location = symbol
            .get("location")
            .ok_or_else(|| LspError::backend_contract("hierarchy symbol missing location"))?;
        let range = self.range_value(location)?;
        let path = self.backend_path(string_field(location, "filePath")?)?;
        Ok(json!({
            "name": symbol_name(symbol),
            "detail": symbol_detail(symbol),
            "kind": symbol_kind_value(symbol),
            "uri": path_to_file_uri(&path.display().to_string()),
            "range": range,
            "selectionRange": range,
            "data": symbol_data(symbol)?
        }))
    }

    fn backend_path(&self, file_path: &str) -> LspResult<PathBuf> {
        let path = config::normalize(PathBuf::from(file_path));
        let workspace_root = self.workspace_root.as_ref().ok_or_else(|| {
            LspError::server_error("LSP_WORKSPACE_UNINITIALIZED", "workspace root is not set")
        })?;
        if !path.starts_with(workspace_root) {
            return Err(LspError::server_error(
                "LSP_BACKEND_PATH_OUTSIDE_WORKSPACE",
                format!(
                    "backend returned path `{}` outside workspace `{}`",
                    path.display(),
                    workspace_root.display()
                ),
            ));
        }
        Ok(path)
    }

    fn workspace_edit_value(&self, edits: &[Value]) -> LspResult<Value> {
        let mut changes: Map<String, Value> = Map::new();
        for edit in edits {
            let path = self.backend_path(string_field(edit, "filePath")?)?;
            if is_generated_path(&path) {
                return Err(LspError::server_error(
                    "LSP_RENAME_GENERATED_PATH",
                    "rename edit would modify generated or build output",
                ));
            }
            let uri = path_to_file_uri(&path.display().to_string());
            let range = self.range_value(edit)?;
            let text_edit = json!({
                "range": range,
                "newText": string_field(edit, "newText")?
            });
            changes
                .entry(uri)
                .or_insert_with(|| Value::Array(Vec::new()))
                .as_array_mut()
                .ok_or_else(|| {
                    LspError::backend_contract("workspace edit bucket was not an array")
                })?
                .push(text_edit);
        }
        Ok(json!({ "changes": changes }))
    }
}

#[derive(Debug)]
struct HierarchyTarget {
    file_path: String,
    offset: usize,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct LspInitializationOptions {
    fail_on_stale_index: bool,
}

fn initialization_options(params: &Value) -> LspResult<LspInitializationOptions> {
    let Some(options) = params.get("initializationOptions") else {
        return Ok(LspInitializationOptions::default());
    };
    if options.is_null() {
        return Ok(LspInitializationOptions::default());
    }
    let options = options
        .as_object()
        .ok_or_else(|| LspError::invalid_params("initializationOptions must be an object"))?;

    if let Some(index_mode) = options.get("indexMode") {
        let index_mode = index_mode.as_str().ok_or_else(|| {
            LspError::invalid_params("initializationOptions.indexMode must be a string")
        })?;
        if index_mode != "compiler-backed" {
            return Err(LspError::invalid_params(format!(
                "unsupported initializationOptions.indexMode `{index_mode}`"
            )));
        }
    }

    if let Some(prefer_compiler_facts) = options.get("preferCompilerFactsOverTextSearch") {
        let prefer_compiler_facts = prefer_compiler_facts.as_bool().ok_or_else(|| {
            LspError::invalid_params(
                "initializationOptions.preferCompilerFactsOverTextSearch must be a boolean",
            )
        })?;
        if !prefer_compiler_facts {
            return Err(LspError::invalid_params(
                "kast lsp requires compiler facts over text search",
            ));
        }
    }

    let fail_on_stale_index = match options.get("failOnStaleIndex") {
        Some(value) => value.as_bool().ok_or_else(|| {
            LspError::invalid_params("initializationOptions.failOnStaleIndex must be a boolean")
        })?,
        None => false,
    };
    Ok(LspInitializationOptions {
        fail_on_stale_index,
    })
}

fn reject_stale_runtime(status: &Value) -> LspResult<()> {
    let state = status
        .get("state")
        .and_then(Value::as_str)
        .ok_or_else(|| LspError::backend_contract("runtime/status missing state"))?;
    let indexing = status
        .get("indexing")
        .and_then(Value::as_bool)
        .ok_or_else(|| LspError::backend_contract("runtime/status missing indexing"))?;
    if state != "READY" || indexing {
        let message = status
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("runtime is not ready");
        return Err(LspError::server_error(
            "LSP_STALE_INDEX",
            format!("LSP initialization requires a ready, non-indexing runtime: {message}"),
        ));
    }
    Ok(())
}

fn hierarchy_target_from_item(item: &Value) -> LspResult<HierarchyTarget> {
    let data = item
        .get("data")
        .ok_or_else(|| LspError::invalid_params("hierarchy item missing data"))?;
    Ok(HierarchyTarget {
        file_path: string_field(data, "filePath")?.to_string(),
        offset: usize_field(data, "offset")?,
    })
}

fn server_capabilities(backend_capabilities: &Value) -> Value {
    json!({
        "textDocumentSync": {
            "openClose": true,
            "change": 1
        },
        "definitionProvider": supports_read(backend_capabilities, "RESOLVE_SYMBOL"),
        "hoverProvider": supports_read(backend_capabilities, "RESOLVE_SYMBOL"),
        "referencesProvider": supports_read(backend_capabilities, "FIND_REFERENCES"),
        "documentSymbolProvider": supports_read(backend_capabilities, "FILE_OUTLINE"),
        "workspaceSymbolProvider": supports_read(backend_capabilities, "WORKSPACE_SYMBOL_SEARCH"),
        "implementationProvider": supports_read(backend_capabilities, "IMPLEMENTATIONS"),
        "callHierarchyProvider": supports_read(backend_capabilities, "CALL_HIERARCHY"),
        "typeHierarchyProvider": supports_read(backend_capabilities, "TYPE_HIERARCHY"),
        "renameProvider": if supports_mutation(backend_capabilities, "RENAME") {
            json!({ "prepareProvider": true })
        } else {
            Value::Bool(false)
        },
        "experimental": {
            "kastMethods": custom_lsp_methods()
        }
    })
}

fn custom_lsp_route(method: &str) -> Option<&'static KastCustomLspRoute> {
    KAST_CUSTOM_LSP_ROUTES
        .iter()
        .find(|route| route.lsp_method == method)
}

fn custom_lsp_methods() -> Vec<&'static str> {
    KAST_CUSTOM_LSP_ROUTES
        .iter()
        .map(|route| route.lsp_method)
        .collect()
}

fn json_rpc_result_from_response(response: &str) -> Result<Value> {
    let value: Value = serde_json::from_str(response)?;
    if let Some(error) = value.get("error") {
        let code = error
            .get("data")
            .and_then(|data| data.get("code"))
            .and_then(Value::as_str)
            .unwrap_or("RPC_ERROR");
        let message = error
            .get("data")
            .and_then(|data| data.get("message"))
            .or_else(|| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("JSON-RPC request failed");
        let mut cli_error = CliError::new("RPC_ERROR", format!("{code}: {message}"));
        cli_error
            .details
            .insert("backendCode".to_string(), code.to_string());
        return Err(cli_error);
    }
    value.get("result").cloned().ok_or_else(|| {
        CliError::new(
            "RPC_RESPONSE_INVALID",
            "JSON-RPC response did not include a result field",
        )
    })
}

fn supports_read(capabilities: &Value, capability: &str) -> bool {
    capabilities
        .get("readCapabilities")
        .and_then(Value::as_array)
        .is_some_and(|values| {
            values
                .iter()
                .any(|value| value.as_str() == Some(capability))
        })
}

fn supports_mutation(capabilities: &Value, capability: &str) -> bool {
    capabilities
        .get("mutationCapabilities")
        .and_then(Value::as_array)
        .is_some_and(|values| {
            values
                .iter()
                .any(|value| value.as_str() == Some(capability))
        })
}

fn validate_rename_symbol(symbol: &Value) -> LspResult<()> {
    let kind = symbol
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("UNKNOWN");
    match kind {
        "CLASS" | "INTERFACE" | "OBJECT" | "FUNCTION" | "PROPERTY" | "PARAMETER" => Ok(()),
        _ => Err(LspError::server_error(
            "LSP_RENAME_UNSUPPORTED_SYMBOL",
            format!("rename is not supported for symbol kind `{kind}`"),
        )),
    }
}

fn validate_new_name(new_name: &str) -> LspResult<()> {
    let mut chars = new_name.chars();
    let Some(first) = chars.next() else {
        return Err(LspError::invalid_params("newName must not be empty"));
    };
    if !(first == '_' || first.is_ascii_alphabetic()) {
        return Err(LspError::invalid_params(
            "newName must start with an ASCII letter or underscore",
        ));
    }
    if !chars.all(|ch| ch == '_' || ch.is_ascii_alphanumeric()) {
        return Err(LspError::invalid_params(
            "newName must contain only ASCII letters, digits, or underscores",
        ));
    }
    Ok(())
}

fn rename_key(position: &Value) -> LspResult<String> {
    Ok(format!(
        "{}:{}",
        string_field(position, "filePath")?,
        usize_field(position, "offset")?
    ))
}

fn reject_non_exhaustive_rename(result: &Value) -> LspResult<()> {
    if result
        .get("searchScope")
        .and_then(|scope| scope.get("exhaustive"))
        .and_then(Value::as_bool)
        .is_some_and(|exhaustive| !exhaustive)
    {
        return Err(LspError::server_error(
            "LSP_RENAME_PARTIAL_REFERENCE_SET",
            "rename was rejected because Kast reported a non-exhaustive reference set",
        ));
    }
    Ok(())
}

fn is_generated_path(path: &Path) -> bool {
    let value = path.to_string_lossy();
    [
        "/build/",
        "/target/",
        "/site/",
        "/generated/",
        "/.gradle/",
        "/.agent-turn/",
    ]
    .iter()
    .any(|segment| value.contains(segment))
}

fn root_from_initialize_params(params: &Value) -> LspResult<Option<PathBuf>> {
    if let Some(root_uri) = params.get("rootUri").and_then(Value::as_str)
        && root_uri != "null"
    {
        return Ok(Some(file_uri_to_path(root_uri)?));
    }
    if let Some(folder_uri) = params
        .get("workspaceFolders")
        .and_then(Value::as_array)
        .and_then(|folders| folders.first())
        .and_then(|folder| folder.get("uri"))
        .and_then(Value::as_str)
    {
        return Ok(Some(file_uri_to_path(folder_uri)?));
    }
    Ok(None)
}

fn read_message<R: BufRead>(reader: &mut R) -> Result<Option<Value>> {
    let mut content_length = None;
    loop {
        let mut header = String::new();
        let read = reader.read_line(&mut header)?;
        if read == 0 {
            return Ok(None);
        }
        let header = header.trim_end_matches(['\r', '\n']);
        if header.is_empty() {
            break;
        }
        if let Some(value) = header.strip_prefix("Content-Length:") {
            content_length = Some(value.trim().parse::<usize>().map_err(|error| {
                CliError::new(
                    "LSP_FRAME_INVALID",
                    format!("invalid Content-Length: {error}"),
                )
            })?);
        }
    }
    let length = content_length.ok_or_else(|| {
        CliError::new(
            "LSP_FRAME_INVALID",
            "LSP message is missing Content-Length header",
        )
    })?;
    let mut buffer = vec![0_u8; length];
    reader.read_exact(&mut buffer)?;
    Ok(Some(serde_json::from_slice(&buffer)?))
}

fn write_message<W: Write>(writer: &mut W, value: &Value) -> Result<()> {
    let body = serde_json::to_vec(value)?;
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())?;
    writer.write_all(&body)?;
    writer.flush()?;
    Ok(())
}

fn success_response(id: Value, result: Value) -> Value {
    json!({
        "jsonrpc": JSONRPC_VERSION,
        "id": id,
        "result": result
    })
}

fn error_response(id: Value, error: LspError) -> Value {
    json!({
        "jsonrpc": JSONRPC_VERSION,
        "id": id,
        "error": {
            "code": error.code,
            "message": error.message,
            "data": {
                "code": error.data_code
            }
        }
    })
}

type LspResult<T> = std::result::Result<T, LspError>;

#[derive(Debug)]
struct LspError {
    code: i64,
    data_code: String,
    message: String,
}

impl LspError {
    fn invalid_request(message: impl Into<String>) -> Self {
        Self {
            code: -32600,
            data_code: "LSP_INVALID_REQUEST".to_string(),
            message: message.into(),
        }
    }

    fn invalid_params(message: impl Into<String>) -> Self {
        Self {
            code: -32602,
            data_code: "LSP_INVALID_PARAMS".to_string(),
            message: message.into(),
        }
    }

    fn method_not_found(message: impl Into<String>) -> Self {
        Self {
            code: -32601,
            data_code: "LSP_METHOD_NOT_FOUND".to_string(),
            message: message.into(),
        }
    }

    fn backend_contract(message: impl Into<String>) -> Self {
        Self::server_error("LSP_BACKEND_CONTRACT_INVALID", message)
    }

    fn server_error(data_code: impl Into<String>, message: impl Into<String>) -> Self {
        Self {
            code: -32000,
            data_code: data_code.into(),
            message: message.into(),
        }
    }
}

impl From<CliError> for LspError {
    fn from(value: CliError) -> Self {
        let data_code = value
            .details
            .get("backendCode")
            .cloned()
            .unwrap_or_else(|| value.code.to_string());
        Self::server_error(data_code, value.message)
    }
}

fn string_field<'a>(value: &'a Value, field: &str) -> LspResult<&'a str> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| LspError::invalid_params(format!("missing string field `{field}`")))
}

fn usize_field(value: &Value, field: &str) -> LspResult<usize> {
    let raw = value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or_else(|| LspError::invalid_params(format!("missing integer field `{field}`")))?;
    usize::try_from(raw)
        .map_err(|_| LspError::invalid_params(format!("field `{field}` is too large")))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LspRange {
    start_line: usize,
    start_character: usize,
    end_line: usize,
    end_character: usize,
}

fn offset_for_position(text: &str, line: usize, character: usize) -> LspResult<usize> {
    let line_start = line_start_offset(text, line)?;
    let line_end = text[line_start..]
        .find('\n')
        .map(|relative| line_start + relative)
        .unwrap_or(text.len());
    let line_text = &text[line_start..line_end];
    let mut utf16 = 0;
    for (relative_offset, ch) in line_text.char_indices() {
        if utf16 == character {
            return Ok(line_start + relative_offset);
        }
        if utf16 > character {
            return Err(LspError::invalid_params(
                "position splits a UTF-16 character",
            ));
        }
        utf16 += ch.len_utf16();
    }
    if utf16 == character {
        return Ok(line_end);
    }
    Err(LspError::invalid_params(format!(
        "character {character} is outside line {line}"
    )))
}

fn line_start_offset(text: &str, target_line: usize) -> LspResult<usize> {
    if target_line == 0 {
        return Ok(0);
    }
    let mut line = 0;
    for (offset, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            line += 1;
            if line == target_line {
                return Ok(offset + 1);
            }
        }
    }
    Err(LspError::invalid_params(format!(
        "line {target_line} is outside document"
    )))
}

fn range_for_offsets(text: &str, start: usize, end: usize) -> LspResult<LspRange> {
    if start > end
        || end > text.len()
        || !text.is_char_boundary(start)
        || !text.is_char_boundary(end)
    {
        return Err(LspError::server_error(
            "LSP_RANGE_INVALID",
            "backend returned invalid byte offsets",
        ));
    }
    let (start_line, start_character) = position_for_offset(text, start)?;
    let (end_line, end_character) = position_for_offset(text, end)?;
    Ok(LspRange {
        start_line,
        start_character,
        end_line,
        end_character,
    })
}

fn position_for_offset(text: &str, offset: usize) -> LspResult<(usize, usize)> {
    if offset > text.len() || !text.is_char_boundary(offset) {
        return Err(LspError::server_error(
            "LSP_RANGE_INVALID",
            "offset is outside the document or not a character boundary",
        ));
    }
    let mut line = 0;
    let mut line_start = 0;
    for (index, byte) in text.bytes().enumerate() {
        if index == offset {
            break;
        }
        if byte == b'\n' {
            line += 1;
            line_start = index + 1;
        }
    }
    let character = text[line_start..offset]
        .chars()
        .map(char::len_utf16)
        .sum::<usize>();
    Ok((line, character))
}

fn file_uri_to_path(uri: &str) -> LspResult<PathBuf> {
    let raw = uri
        .strip_prefix("file://")
        .ok_or_else(|| LspError::invalid_params(format!("unsupported URI `{uri}`")))?;
    let path = if let Some(path) = raw.strip_prefix("localhost/") {
        format!("/{path}")
    } else if raw.starts_with('/') {
        raw.to_string()
    } else {
        return Err(LspError::invalid_params(format!(
            "unsupported file URI authority in `{uri}`"
        )));
    };
    Ok(PathBuf::from(percent_decode(&path)?))
}

fn path_to_file_uri(path: &str) -> String {
    format!("file://{}", percent_encode_path(path))
}

fn percent_decode(value: &str) -> LspResult<String> {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return Err(LspError::invalid_params("incomplete percent escape in URI"));
            }
            let hex = std::str::from_utf8(&bytes[index + 1..index + 3])
                .map_err(|_| LspError::invalid_params("invalid percent escape"))?;
            let byte = u8::from_str_radix(hex, 16)
                .map_err(|_| LspError::invalid_params("invalid percent escape"))?;
            decoded.push(byte);
            index += 3;
        } else {
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(decoded).map_err(|_| LspError::invalid_params("URI path is not UTF-8"))
}

fn percent_encode_path(path: &str) -> String {
    let mut encoded = String::with_capacity(path.len());
    for byte in path.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'/' | b'.' | b'-' | b'_' | b'~' => {
                encoded.push(byte as char)
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}

fn symbol_name(symbol: &Value) -> String {
    let fq_name = symbol
        .get("fqName")
        .and_then(Value::as_str)
        .unwrap_or("symbol");
    fq_name
        .rsplit('.')
        .next()
        .filter(|name| !name.is_empty())
        .unwrap_or(fq_name)
        .to_string()
}

fn symbol_detail(symbol: &Value) -> String {
    let fq_name = symbol.get("fqName").and_then(Value::as_str).unwrap_or("");
    let kind = symbol
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("SYMBOL");
    let type_label = symbol
        .get("returnType")
        .or_else(|| symbol.get("type"))
        .and_then(Value::as_str);
    match type_label {
        Some(label) if !label.is_empty() => format!("{kind} {fq_name}: {label}"),
        _ => format!("{kind} {fq_name}"),
    }
}

fn hover_markdown(symbol: &Value) -> String {
    let mut lines = vec![format!("```kotlin\n{}\n```", symbol_detail(symbol))];
    if let Some(documentation) = symbol.get("documentation").and_then(Value::as_str)
        && !documentation.trim().is_empty()
    {
        lines.push(documentation.trim().to_string());
    }
    lines.join("\n\n")
}

fn symbol_kind_value(symbol: &Value) -> u64 {
    match symbol
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("UNKNOWN")
    {
        "CLASS" => 5,
        "INTERFACE" => 11,
        "OBJECT" => 19,
        "FUNCTION" => 12,
        "PROPERTY" => 7,
        "PARAMETER" => 13,
        _ => 13,
    }
}

fn symbol_data(symbol: &Value) -> LspResult<Value> {
    let location = symbol
        .get("location")
        .ok_or_else(|| LspError::backend_contract("symbol missing location"))?;
    Ok(json!({
        "filePath": string_field(location, "filePath")?,
        "offset": usize_field(location, "startOffset")?,
        "fqName": symbol.get("fqName").cloned().unwrap_or(Value::Null),
        "kind": symbol.get("kind").cloned().unwrap_or(Value::Null)
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct FakeRpc {
        workspace_root: Option<PathBuf>,
        capabilities: Value,
        calls: RefCell<Vec<(String, Value)>>,
        responses: RefCell<HashMap<String, Value>>,
        errors: RefCell<HashMap<String, (String, String)>>,
    }

    impl FakeRpc {
        fn new(workspace_root: PathBuf) -> Self {
            Self {
                workspace_root: Some(workspace_root),
                capabilities: json!({
                    "readCapabilities": [
                        "RESOLVE_SYMBOL",
                        "FIND_REFERENCES",
                        "FILE_OUTLINE",
                        "WORKSPACE_SYMBOL_SEARCH",
                        "IMPLEMENTATIONS",
                        "CALL_HIERARCHY",
                        "TYPE_HIERARCHY"
                    ],
                    "mutationCapabilities": [
                        "RENAME"
                    ]
                }),
                calls: RefCell::new(Vec::new()),
                responses: RefCell::new(HashMap::new()),
                errors: RefCell::new(HashMap::new()),
            }
        }

        fn respond(&self, method: &str, response: Value) {
            self.responses
                .borrow_mut()
                .insert(method.to_string(), response);
        }

        fn fail_with_backend_code(&self, method: &str, backend_code: &str, message: &str) {
            self.errors.borrow_mut().insert(
                method.to_string(),
                (backend_code.to_string(), message.to_string()),
            );
        }
    }

    impl KastRpcClient for FakeRpc {
        fn initial_workspace_root(&self) -> Option<PathBuf> {
            self.workspace_root.clone()
        }

        fn set_workspace_root(&mut self, workspace_root: PathBuf) {
            self.workspace_root = Some(workspace_root);
        }

        fn capabilities(&mut self) -> Result<Value> {
            Ok(self.capabilities.clone())
        }

        fn request(&mut self, method: &str, params: Value) -> Result<Value> {
            self.calls.borrow_mut().push((method.to_string(), params));
            if let Some((backend_code, message)) = self.errors.borrow().get(method) {
                let mut error = CliError::new("RPC_ERROR", format!("{backend_code}: {message}"));
                error
                    .details
                    .insert("backendCode".to_string(), backend_code.clone());
                return Err(error);
            }
            self.responses
                .borrow()
                .get(method)
                .cloned()
                .ok_or_else(|| CliError::new("TEST_MISSING_RESPONSE", method.to_string()))
        }
    }

    #[test]
    fn lsp_framing_round_trips_json_messages() {
        let value = json!({"jsonrpc":"2.0","id":1,"method":"initialize"});
        let mut bytes = Vec::new();
        write_message(&mut bytes, &value).expect("write message");
        let mut cursor = io::Cursor::new(bytes);
        let decoded = read_message(&mut cursor)
            .expect("read result")
            .expect("message");
        assert_eq!(decoded, value);
    }

    #[test]
    fn lifecycle_runs_over_framed_stdio_until_exit() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        let mut server = LspServer::new(rpc);
        let mut input = Vec::new();
        write_message(
            &mut input,
            &json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": { "rootUri": path_to_file_uri(&temp.path().display().to_string()) }
            }),
        )
        .expect("initialize frame");
        write_message(
            &mut input,
            &json!({
                "jsonrpc": "2.0",
                "method": "initialized",
                "params": {}
            }),
        )
        .expect("initialized frame");
        write_message(
            &mut input,
            &json!({
                "jsonrpc": "2.0",
                "id": 2,
                "method": "shutdown",
                "params": {}
            }),
        )
        .expect("shutdown frame");
        write_message(
            &mut input,
            &json!({
                "jsonrpc": "2.0",
                "method": "exit",
                "params": {}
            }),
        )
        .expect("exit frame");

        let mut output = Vec::new();
        server
            .serve(io::Cursor::new(input), &mut output)
            .expect("serve");

        let mut output_cursor = io::Cursor::new(output);
        let initialize_response = read_message(&mut output_cursor)
            .expect("read initialize")
            .expect("initialize response");
        let shutdown_response = read_message(&mut output_cursor)
            .expect("read shutdown")
            .expect("shutdown response");
        assert_eq!(initialize_response["id"], 1);
        assert_eq!(
            initialize_response["result"]["serverInfo"]["name"],
            "kast-lsp"
        );
        assert_eq!(shutdown_response["id"], 2);
        assert_eq!(shutdown_response["result"], Value::Null);
        assert!(server.shutdown_requested);
        assert!(server.exited);
    }

    #[test]
    fn utf16_position_mapping_handles_surrogate_pairs() {
        let text = "fun main() {\n  val note = \"𝄞\"\n}\n";
        let note_offset = text.find('𝄞').expect("note");
        assert_eq!(
            offset_for_position(text, 1, 14).expect("offset"),
            note_offset
        );
        assert_eq!(
            range_for_offsets(text, note_offset, note_offset + "𝄞".len()).expect("range"),
            LspRange {
                start_line: 1,
                start_character: 14,
                end_line: 1,
                end_character: 16,
            }
        );
    }

    #[test]
    fn file_uri_conversion_preserves_spaces() {
        let path = "/tmp/kast lsp/Sample.kt";
        let uri = path_to_file_uri(path);
        assert_eq!(uri, "file:///tmp/kast%20lsp/Sample.kt");
        assert_eq!(file_uri_to_path(&uri).expect("path"), PathBuf::from(path));
    }

    #[test]
    fn initialize_advertises_only_backend_supported_read_capabilities() {
        let temp = tempfile::tempdir().expect("temp");
        let mut rpc = FakeRpc::new(temp.path().to_path_buf());
        rpc.capabilities = json!({
            "readCapabilities": ["RESOLVE_SYMBOL", "CALL_HIERARCHY"]
        });
        let mut server = LspServer::new(rpc);
        let result = server.initialize(json!({})).expect("initialize");
        let caps = &result["capabilities"];
        assert_eq!(caps["definitionProvider"], true);
        assert_eq!(caps["hoverProvider"], true);
        assert_eq!(caps["callHierarchyProvider"], true);
        assert_eq!(caps["referencesProvider"], false);
        assert_eq!(caps["typeHierarchyProvider"], false);
        assert_eq!(caps["renameProvider"], false);
    }

    #[test]
    fn initialize_rejects_indexing_runtime_when_stale_index_must_fail_closed() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        rpc.respond(
            "runtime/status",
            json!({
                "state": "INDEXING",
                "healthy": true,
                "active": true,
                "indexing": true,
                "backendName": "idea",
                "backendVersion": "test",
                "workspaceRoot": temp.path().display().to_string(),
                "message": "IDEA is indexing",
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        let error = server
            .initialize(json!({
                "initializationOptions": {
                    "indexMode": "compiler-backed",
                    "failOnStaleIndex": true,
                    "preferCompilerFactsOverTextSearch": true
                }
            }))
            .expect_err("indexing runtime should fail closed");
        assert_eq!(error.data_code, "LSP_STALE_INDEX");
    }

    #[test]
    fn custom_kast_methods_forward_to_matching_rpc_methods() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        let mappings = custom_method_mappings();
        for (_, rpc_method) in &mappings {
            rpc.respond(
                rpc_method,
                json!({
                    "type": "TEST_SUCCESS",
                    "method": rpc_method
                }),
            );
        }
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");

        for (lsp_method, rpc_method) in &mappings {
            let result = server
                .handle_request(lsp_method, json!({ "marker": lsp_method }))
                .unwrap_or_else(|error| panic!("{lsp_method} failed: {}", error.message));
            assert_eq!(result["method"], rpc_method.as_str());
        }

        let calls = server.rpc.calls.borrow();
        assert_eq!(calls.len(), mappings.len());
        for ((lsp_method, rpc_method), (actual_method, params)) in mappings.iter().zip(calls.iter())
        {
            assert_eq!(actual_method, rpc_method, "{lsp_method} routed incorrectly");
            assert_eq!(params["marker"], lsp_method.as_str());
        }
    }

    #[test]
    fn custom_symbol_methods_inject_workspace_root_when_missing() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond("symbol/references", json!({ "type": "REFERENCES_SUCCESS" }));
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");

        server
            .handle_request("kast/symbolReferences", json!({ "symbol": "greet" }))
            .expect("symbol references");

        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[0].0, "symbol/references");
        assert_eq!(calls[0].1["workspaceRoot"], workspace.display().to_string());
    }

    #[test]
    fn custom_symbol_methods_preserve_explicit_workspace_root() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        rpc.respond("symbol/resolve", json!({ "type": "RESOLVE_SUCCESS" }));
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");

        server
            .handle_request(
                "kast/symbolResolve",
                json!({
                    "workspaceRoot": "/explicit/workspace",
                    "symbol": "greet"
                }),
            )
            .expect("symbol resolve");

        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[0].0, "symbol/resolve");
        assert_eq!(calls[0].1["workspaceRoot"], "/explicit/workspace");
    }

    #[test]
    fn initialize_advertises_custom_kast_methods_experimentally() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        let mut server = LspServer::new(rpc);
        let result = server.initialize(json!({})).expect("initialize");
        let methods = result["capabilities"]["experimental"]["kastMethods"]
            .as_array()
            .expect("kastMethods");
        let methods = methods
            .iter()
            .map(|method| method.as_str().expect("method string"))
            .collect::<Vec<_>>();
        let mappings = custom_method_mappings();
        let expected = mappings
            .iter()
            .map(|(lsp_method, _)| lsp_method.as_str())
            .collect::<Vec<_>>();
        assert_eq!(methods, expected);
    }

    #[test]
    fn custom_lsp_routes_match_rpc_catalog() {
        let catalog: Value = serde_json::from_str(include_str!(
            "../resources/kast-skill/references/commands.json"
        ))
        .expect("commands catalog");
        let expected = expected_custom_routes_from_catalog(&catalog);
        assert_eq!(custom_method_mappings(), expected);
    }

    #[test]
    fn custom_kast_backend_errors_are_wrapped_as_lsp_errors() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        rpc.fail_with_backend_code(
            "symbol/resolve",
            "AMBIGUOUS_ANCHOR",
            "multiple declarations matched the requested anchor",
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");

        let response = server
            .handle_message(json!({
                "jsonrpc": "2.0",
                "id": 99,
                "method": "kast/symbolResolve",
                "params": { "symbol": "greet" }
            }))
            .expect("response");

        assert_eq!(response["id"], 99);
        assert_eq!(response["error"]["code"], -32000);
        assert_eq!(response["error"]["data"]["code"], "AMBIGUOUS_ANCHOR");
        assert!(
            response["error"]["message"]
                .as_str()
                .expect("message")
                .contains("multiple declarations")
        );
    }

    #[test]
    fn definition_maps_lsp_position_to_raw_resolve() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION")
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .definition(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("definition");
        assert_eq!(result["uri"], path_to_file_uri(&file.display().to_string()));
        assert_eq!(result["range"]["start"]["line"], 2);
        assert_eq!(result["range"]["start"]["character"], 4);
        assert_eq!(
            server.rpc.calls.borrow()[0].0,
            "raw/resolve",
            "definition should call raw/resolve"
        );
    }

    #[test]
    fn references_map_lsp_position_and_include_declaration_to_raw_references() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        let source = "package sample\n\nfun greet() = Unit\nfun caller() = greet()\n";
        fs::write(&file, source).expect("fixture");
        let declaration_start = source.find("greet").expect("declaration");
        let call_start = source.rfind("greet").expect("call");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/references",
            json!({
                "references": [
                    location(&file, declaration_start, declaration_start + "greet".len()),
                    location(&file, call_start, call_start + "greet".len())
                ],
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .references(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 3, "character": 15 },
                "context": { "includeDeclaration": true }
            }))
            .expect("references");
        let references = result.as_array().expect("references");
        assert_eq!(references.len(), 2);
        assert_eq!(references[1]["range"]["start"]["line"], 3);
        assert_eq!(references[1]["range"]["start"]["character"], 15);
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[0].0, "raw/references");
        assert_eq!(calls[0].1["includeDeclaration"], true);
        assert_eq!(calls[0].1["position"]["offset"], call_start);
    }

    #[test]
    fn hover_returns_compact_symbol_markdown_from_raw_resolve() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": {
                    "fqName": "sample.greet",
                    "kind": "FUNCTION",
                    "location": location(&file, 20, 25),
                    "returnType": "Unit",
                    "documentation": "Greets the caller."
                }
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .hover(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("hover");
        let value = result["contents"]["value"]
            .as_str()
            .expect("hover markdown");
        assert!(value.contains("FUNCTION sample.greet: Unit"));
        assert!(value.contains("Greets the caller."));
        assert!(!value.contains("package sample"));
    }

    #[test]
    fn document_symbols_map_nested_outline_without_file_contents() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        let source = "package sample\n\nclass Greeter {\n  fun greet() = Unit\n}\n";
        fs::write(&file, source).expect("fixture");
        let class_start = source.find("Greeter").expect("class");
        let function_start = source.find("greet").expect("function");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/file-outline",
            json!({
                "symbols": [{
                    "symbol": sample_symbol(
                        &file,
                        class_start,
                        class_start + "Greeter".len(),
                        "sample.Greeter",
                        "CLASS"
                    ),
                    "children": [{
                        "symbol": sample_symbol(
                            &file,
                            function_start,
                            function_start + "greet".len(),
                            "sample.Greeter.greet",
                            "FUNCTION"
                        ),
                        "children": []
                    }]
                }],
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .document_symbol(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) }
            }))
            .expect("document symbols");
        let symbols = result.as_array().expect("symbols");
        assert_eq!(symbols.len(), 1);
        assert_eq!(symbols[0]["name"], "Greeter");
        assert_eq!(symbols[0]["children"][0]["name"], "greet");
        assert!(symbols[0].get("text").is_none());
        assert_eq!(server.rpc.calls.borrow()[0].0, "raw/file-outline");
    }

    #[test]
    fn workspace_symbols_are_bounded_and_location_oriented() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        let symbols = (0..(MAX_LSP_RESULTS + 5))
            .map(|index| sample_symbol(&file, 20, 25, &format!("sample.Symbol{index}"), "FUNCTION"))
            .collect::<Vec<_>>();
        rpc.respond(
            "raw/workspace-symbol",
            json!({
                "symbols": symbols,
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .workspace_symbol(json!({ "query": "Symbol" }))
            .expect("workspace symbols");
        let symbols = result.as_array().expect("symbols");
        assert_eq!(symbols.len(), MAX_LSP_RESULTS);
        assert_eq!(
            symbols[0]["location"]["uri"],
            path_to_file_uri(&file.display().to_string())
        );
        assert!(symbols[0].get("text").is_none());
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[0].0, "raw/workspace-symbol");
        assert_eq!(calls[0].1["maxResults"], MAX_LSP_RESULTS);
    }

    #[test]
    fn implementation_maps_symbols_to_lsp_locations() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        let source = "package sample\n\ninterface Greeter\nclass FriendlyGreeter : Greeter\n";
        fs::write(&file, source).expect("fixture");
        let implementation_start = source.find("FriendlyGreeter").expect("implementation");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/implementations",
            json!({
                "implementations": [
                    sample_symbol(
                        &file,
                        implementation_start,
                        implementation_start + "FriendlyGreeter".len(),
                        "sample.FriendlyGreeter",
                        "CLASS"
                    )
                ],
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .implementation(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 10 }
            }))
            .expect("implementation");
        let implementations = result.as_array().expect("implementations");
        assert_eq!(implementations.len(), 1);
        assert_eq!(implementations[0]["range"]["start"]["line"], 3);
        assert_eq!(implementations[0]["range"]["start"]["character"], 6);
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[0].0, "raw/implementations");
        assert_eq!(calls[0].1["maxResults"], MAX_LSP_RESULTS);
    }

    #[test]
    fn initialize_advertises_prepare_rename_when_backend_supports_rename() {
        let temp = tempfile::tempdir().expect("temp");
        let rpc = FakeRpc::new(temp.path().to_path_buf());
        let mut server = LspServer::new(rpc);
        let result = server.initialize(json!({})).expect("initialize");
        assert_eq!(
            result["capabilities"]["renameProvider"],
            json!({ "prepareProvider": true })
        );
    }

    #[test]
    fn prepare_rename_resolves_symbol_and_records_exact_target() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION")
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let result = server
            .prepare_rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("prepare rename");
        assert_eq!(result["placeholder"], "greet");
        assert_eq!(result["range"]["start"]["line"], 2);
        assert_eq!(result["range"]["start"]["character"], 4);
        assert!(
            server
                .prepared_renames
                .contains(&format!("{}:20", file.display()))
        );
        assert_eq!(server.rpc.calls.borrow()[0].0, "raw/resolve");
    }

    #[test]
    fn rename_requires_successful_prepare_for_same_position() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let error = server
            .rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 },
                "newName": "welcome"
            }))
            .expect_err("rename should require prepare");
        assert_eq!(error.data_code, "LSP_RENAME_NOT_PREPARED");
    }

    #[test]
    fn rename_maps_raw_rename_plan_to_workspace_edit() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        let source = "package sample\n\nfun greet() = Unit\nfun caller() = greet()\n";
        fs::write(&file, source).expect("fixture");
        let declaration_start = source.find("greet").expect("declaration");
        let call_start = source.rfind("greet").expect("call");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(
                    &file,
                    declaration_start,
                    declaration_start + "greet".len(),
                    "sample.greet",
                    "FUNCTION"
                )
            }),
        );
        rpc.respond(
            "raw/rename",
            json!({
                "edits": [
                    {
                        "filePath": file.display().to_string(),
                        "startOffset": declaration_start,
                        "endOffset": declaration_start + "greet".len(),
                        "newText": "welcome"
                    },
                    {
                        "filePath": file.display().to_string(),
                        "startOffset": call_start,
                        "endOffset": call_start + "greet".len(),
                        "newText": "welcome"
                    }
                ],
                "fileHashes": [],
                "affectedFiles": [file.display().to_string()],
                "searchScope": {
                    "visibility": "PUBLIC",
                    "scope": "DEPENDENT_MODULES",
                    "exhaustive": true,
                    "candidateFileCount": 1,
                    "searchedFileCount": 1
                },
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        server
            .prepare_rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("prepare rename");
        let result = server
            .rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 },
                "newName": "welcome"
            }))
            .expect("rename");
        let uri = path_to_file_uri(&file.display().to_string());
        let edits = result["changes"][&uri].as_array().expect("edits");
        assert_eq!(edits.len(), 2);
        assert_eq!(edits[0]["newText"], "welcome");
        assert_eq!(edits[0]["range"]["start"]["line"], 2);
        assert_eq!(edits[1]["range"]["start"]["line"], 3);
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[1].0, "raw/rename");
        assert_eq!(calls[1].1["newName"], "welcome");
        assert_eq!(calls[1].1["dryRun"], true);
    }

    #[test]
    fn rename_rejects_invalid_new_name_before_backend_call() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let error = server
            .rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 },
                "newName": "not-valid"
            }))
            .expect_err("invalid newName should fail");
        assert_eq!(error.data_code, "LSP_INVALID_PARAMS");
        assert!(server.rpc.calls.borrow().is_empty());
    }

    #[test]
    fn rename_rejects_non_exhaustive_reference_sets() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION")
            }),
        );
        rpc.respond(
            "raw/rename",
            json!({
                "edits": [],
                "fileHashes": [],
                "affectedFiles": [],
                "searchScope": {
                    "visibility": "PUBLIC",
                    "scope": "DEPENDENT_MODULES",
                    "exhaustive": false,
                    "candidateFileCount": 10,
                    "searchedFileCount": 2
                },
                "schemaVersion": 3
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        server
            .prepare_rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("prepare rename");
        let error = server
            .rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 },
                "newName": "welcome"
            }))
            .expect_err("non-exhaustive rename should fail");
        assert_eq!(error.data_code, "LSP_RENAME_PARTIAL_REFERENCE_SET");
    }

    #[test]
    fn prepare_rename_rejects_generated_paths() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let generated_dir = workspace.join("build/generated");
        fs::create_dir_all(&generated_dir).expect("generated dir");
        let file = generated_dir.join("Sample.kt");
        fs::write(&file, "package sample\n\nfun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION")
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let error = server
            .prepare_rename(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect_err("generated rename should fail");
        assert_eq!(error.data_code, "LSP_RENAME_GENERATED_PATH");
    }

    #[test]
    fn hierarchy_requests_use_item_data_for_follow_up_rpc() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(
            &file,
            "package sample\n\nfun greet() = Unit\nfun caller() = greet()\n",
        )
        .expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION")
            }),
        );
        rpc.respond(
            "raw/call-hierarchy",
            json!({
                "root": {
                    "symbol": sample_symbol(&file, 20, 25, "sample.greet", "FUNCTION"),
                    "children": [{
                        "symbol": sample_symbol(&file, 39, 45, "sample.caller", "FUNCTION"),
                        "callSite": location(&file, 50, 55),
                        "children": []
                    }]
                },
                "stats": {
                    "totalNodes": 2,
                    "totalEdges": 1,
                    "truncatedNodes": 0,
                    "maxDepthReached": 1,
                    "timeoutReached": false,
                    "maxTotalCallsReached": false,
                    "maxChildrenPerNodeReached": false,
                    "filesVisited": 1
                }
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let prepared = server
            .prepare_call_hierarchy(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 4 }
            }))
            .expect("prepare");
        let item = prepared.as_array().expect("array")[0].clone();
        let incoming = server
            .call_hierarchy(json!({ "item": item }), "INCOMING")
            .expect("incoming");
        assert_eq!(incoming.as_array().expect("incoming").len(), 1);
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[1].0, "raw/call-hierarchy");
        assert_eq!(calls[1].1["position"]["offset"], 20);
        assert_eq!(calls[1].1["direction"], "INCOMING");
    }

    #[test]
    fn outgoing_call_hierarchy_uses_lsp_outgoing_call_shape() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        let source = "package sample\n\nfun greet() = Unit\nfun caller() = greet()\n";
        fs::write(&file, source).expect("fixture");
        let caller_start = source.find("caller").expect("caller");
        let greet_start = source.find("greet").expect("greet");
        let call_site_start = source.rfind("greet").expect("call site");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(
                    &file,
                    caller_start,
                    caller_start + "caller".len(),
                    "sample.caller",
                    "FUNCTION"
                )
            }),
        );
        rpc.respond(
            "raw/call-hierarchy",
            json!({
                "root": {
                    "symbol": sample_symbol(
                        &file,
                        caller_start,
                        caller_start + "caller".len(),
                        "sample.caller",
                        "FUNCTION"
                    ),
                    "children": [{
                        "symbol": sample_symbol(
                            &file,
                            greet_start,
                            greet_start + "greet".len(),
                            "sample.greet",
                            "FUNCTION"
                        ),
                        "callSite": location(&file, call_site_start, call_site_start + "greet".len()),
                        "children": []
                    }]
                }
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let prepared = server
            .prepare_call_hierarchy(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 3, "character": 4 }
            }))
            .expect("prepare");
        let item = prepared.as_array().expect("array")[0].clone();
        let outgoing = server
            .call_hierarchy(json!({ "item": item }), "OUTGOING")
            .expect("outgoing");
        let calls = outgoing.as_array().expect("outgoing");
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0]["to"]["name"], "greet");
        assert!(calls[0].get("from").is_none());
        assert_eq!(calls[0]["fromRanges"][0]["start"]["line"], 3);
    }

    #[test]
    fn type_hierarchy_requests_use_item_data_for_follow_up_rpc() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(
            &file,
            "package sample\n\ninterface Greeter\nclass FriendlyGreeter : Greeter\n",
        )
        .expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.respond(
            "raw/resolve",
            json!({
                "symbol": sample_symbol(&file, 26, 33, "sample.Greeter", "INTERFACE")
            }),
        );
        rpc.respond(
            "raw/type-hierarchy",
            json!({
                "root": {
                    "symbol": sample_symbol(&file, 26, 33, "sample.Greeter", "INTERFACE"),
                    "children": [{
                        "symbol": sample_symbol(&file, 40, 55, "sample.FriendlyGreeter", "CLASS"),
                        "children": []
                    }]
                },
                "stats": {
                    "totalNodes": 2,
                    "maxDepthReached": 1,
                    "truncated": false
                }
            }),
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let prepared = server
            .prepare_type_hierarchy(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 2, "character": 10 }
            }))
            .expect("prepare");
        let item = prepared.as_array().expect("array")[0].clone();
        let subtypes = server
            .type_hierarchy(json!({ "item": item }), "SUBTYPES")
            .expect("subtypes");
        assert_eq!(subtypes.as_array().expect("subtypes").len(), 1);
        let calls = server.rpc.calls.borrow();
        assert_eq!(calls[1].0, "raw/type-hierarchy");
        assert_eq!(calls[1].1["position"]["offset"], 26);
        assert_eq!(calls[1].1["direction"], "SUBTYPES");
    }

    #[test]
    fn dirty_buffers_fail_closed() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "fun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        server
            .did_open(json!({
                "textDocument": {
                    "uri": path_to_file_uri(&file.display().to_string()),
                    "text": "fun changed() = Unit\n"
                }
            }))
            .expect("didOpen");
        let error = server
            .definition(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 0, "character": 4 }
            }))
            .expect_err("dirty buffer should fail");
        assert_eq!(error.data_code, "LSP_UNSAVED_BUFFER_UNSUPPORTED");
    }

    #[test]
    fn backend_ambiguity_errors_remain_explicit_in_lsp_error_data() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "fun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.fail_with_backend_code(
            "raw/resolve",
            "AMBIGUOUS_ANCHOR",
            "multiple declarations matched the requested anchor",
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let error = server
            .definition(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 0, "character": 4 }
            }))
            .expect_err("ambiguous symbol should fail closed");
        assert_eq!(error.data_code, "AMBIGUOUS_ANCHOR");
        assert!(error.message.contains("multiple declarations"));
    }

    #[test]
    fn stale_or_not_ready_backend_errors_remain_explicit_in_lsp_error_data() {
        let temp = tempfile::tempdir().expect("temp");
        let workspace = temp.path();
        let file = workspace.join("Sample.kt");
        fs::write(&file, "fun greet() = Unit\n").expect("fixture");
        let rpc = FakeRpc::new(workspace.to_path_buf());
        rpc.fail_with_backend_code(
            "raw/references",
            "RUNTIME_TIMEOUT",
            "Timed out waiting for headless runtime to become ready",
        );
        let mut server = LspServer::new(rpc);
        server.initialize(json!({})).expect("initialize");
        let error = server
            .references(json!({
                "textDocument": { "uri": path_to_file_uri(&file.display().to_string()) },
                "position": { "line": 0, "character": 4 },
                "context": { "includeDeclaration": false }
            }))
            .expect_err("not-ready backend should fail closed");
        assert_eq!(error.data_code, "RUNTIME_TIMEOUT");
        assert!(error.message.contains("Timed out"));
    }

    fn sample_symbol(file: &Path, start: usize, end: usize, fq_name: &str, kind: &str) -> Value {
        json!({
            "fqName": fq_name,
            "kind": kind,
            "location": location(file, start, end),
            "returnType": "Unit"
        })
    }

    fn location(file: &Path, start: usize, end: usize) -> Value {
        json!({
            "filePath": file.display().to_string(),
            "startOffset": start,
            "endOffset": end,
            "startLine": 1,
            "startColumn": 1,
            "preview": "sample"
        })
    }

    fn custom_method_mappings() -> Vec<(String, String)> {
        KAST_CUSTOM_LSP_ROUTES
            .iter()
            .map(|route| (route.lsp_method.to_string(), route.rpc_method.to_string()))
            .collect()
    }

    fn expected_custom_routes_from_catalog(catalog: &Value) -> Vec<(String, String)> {
        let categories = catalog["categories"].as_object().expect("categories");
        let commands = catalog["commands"].as_object().expect("commands");
        ["symbol", "database", "system"]
            .into_iter()
            .flat_map(|category| {
                categories[category]
                    .as_array()
                    .unwrap_or_else(|| panic!("category {category} methods"))
                    .iter()
                    .map(|method| {
                        let method = method.as_str().expect("method string");
                        assert!(
                            commands.contains_key(method),
                            "category references missing method {method}"
                        );
                        let lsp_method = lsp_method_for_rpc_method(method);
                        (lsp_method, method.to_string())
                    })
                    .collect::<Vec<_>>()
            })
            .collect()
    }

    fn lsp_method_for_rpc_method(method: &str) -> String {
        let mut parts = method.split('/');
        let first = parts.next().expect("first method segment");
        let mut lsp_method = format!("kast/{first}");
        for part in parts {
            for word in part.split('-') {
                let mut chars = word.chars();
                if let Some(first) = chars.next() {
                    lsp_method.push(first.to_ascii_uppercase());
                    lsp_method.extend(chars);
                }
            }
        }
        lsp_method
    }
}
