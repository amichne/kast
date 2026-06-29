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
