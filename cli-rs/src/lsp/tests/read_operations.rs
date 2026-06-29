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
