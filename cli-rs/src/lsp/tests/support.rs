
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
