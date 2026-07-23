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
                "schemaVersion": 5
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
            "../../../protocol/source/commands.json"
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
