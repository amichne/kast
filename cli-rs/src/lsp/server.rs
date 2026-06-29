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
                eprintln!("kast agent lsp notification error: {}", error.message);
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
