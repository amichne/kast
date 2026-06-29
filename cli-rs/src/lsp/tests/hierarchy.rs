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
