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
