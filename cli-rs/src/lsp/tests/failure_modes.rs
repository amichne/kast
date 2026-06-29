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
