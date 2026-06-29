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
                "kast agent lsp requires compiler facts over text search",
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
