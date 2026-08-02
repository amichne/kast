use serde_json::{Value, json};
use std::process::Output;
use std::time::{Duration, Instant};
use support::*;

#[test]
fn relative_file_paths_are_canonical_in_every_compact_json_view() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let first = workspace.join("src/First.kt");
    let second = workspace.join("src/with spaces/Second.kt");
    for file in [&first, &second] {
        std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
        std::fs::write(file, "class Example\n").expect("scenario source");
    }
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");

    let expected = [&first, &second].map(|file| {
        file.canonicalize()
            .expect("canonical source")
            .display()
            .to_string()
    });
    let socket_path = workspace_socket_path(&workspace, temp.path());
    let listener = bind_listener(&socket_path);
    write_descriptor(&home, &workspace, &socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh_for(&expected),
        complete_clean_diagnostics_for(&expected),
        12,
    );
    let views: [&[&str]; 3] = [&[], &["--fields", "analysis"], &["--count"]];
    let outputs = views.map(|view| {
        run_diagnostics_arguments_with_view(
            &home,
            &config_home,
            &workspace,
            &["src/First.kt", "src/with spaces/Second.kt"],
            "json",
            view,
        )
    });
    for output in &outputs {
        assert!(
            output.status.success(),
            "relative diagnostics should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
    let requests = backend.join().expect("fake diagnostics backend");

    let refresh_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/workspace-refresh")
        .collect::<Vec<_>>();
    let diagnostics_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/diagnostics")
        .collect::<Vec<_>>();
    assert_eq!(refresh_requests.len(), 3, "requests={requests:#?}");
    assert_eq!(diagnostics_requests.len(), 3, "requests={requests:#?}");
    for request in refresh_requests {
        assert_eq!(
            request["params"]["filePaths"],
            json!(expected),
            "refresh request: {request:#}",
        );
    }
    for request in diagnostics_requests {
        assert_eq!(
            request["params"]["filePaths"],
            json!(expected),
            "diagnostics request: {request:#}",
        );
    }
    for output in outputs {
        let document = decode_json(&output);
        assert_eq!(document["result"]["filePaths"], json!(expected));
        assert_eq!(
            document["result"]["fileHashes"],
            json!(
                expected
                    .iter()
                    .map(|file_path| json!({"filePath": file_path, "hash": "a".repeat(64)}))
                    .collect::<Vec<_>>()
            ),
        );
    }
}

#[test]
fn canonical_relative_path_is_reported_in_every_output_format() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/with spaces/Report.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, "class Report\n").expect("scenario source");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    let expected = file
        .canonicalize()
        .expect("canonical source")
        .display()
        .to_string();

    let socket_path = workspace_socket_path(&workspace, temp.path());
    let listener = bind_listener(&socket_path);
    write_descriptor(&home, &workspace, &socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh_for(std::slice::from_ref(&expected)),
        complete_clean_diagnostics_for(std::slice::from_ref(&expected)),
        12,
    );
    let outputs = ["json", "human", "toon"].map(|format| {
        run_diagnostics_arguments(
            &home,
            &config_home,
            &workspace,
            &["src/with spaces/Report.kt"],
            format,
        )
    });
    let requests = backend.join().expect("fake diagnostics backend");

    for (format, output) in ["json", "human", "toon"].into_iter().zip(&outputs) {
        let document = if format == "toon" {
            decode_toon(output)
        } else {
            decode_json(output)
        };
        assert!(output.status.success(), "{format}: {document:#}");
        assert_eq!(
            document["result"]["filePaths"],
            json!([expected]),
            "{format}: {document:#}",
        );
    }
    assert_eq!(
        request_methods(&requests),
        expected_diagnostics_methods().repeat(3)
    );
}

#[test]
fn deleted_relative_file_reaches_refresh_with_canonical_path() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_parent = workspace.join("src/deleted");
    std::fs::create_dir_all(&source_parent).expect("source parent");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    let missing = source_parent
        .canonicalize()
        .expect("canonical source parent")
        .join("Removed.kt");

    let socket_path = workspace_socket_path(&workspace, temp.path());
    let listener = bind_listener(&socket_path);
    write_descriptor(&home, &workspace, &socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_removed_refresh(&missing),
        incomplete_diagnostics(&missing),
        4,
    );
    let output = run_diagnostics_arguments(
        &home,
        &config_home,
        &workspace,
        &["src/deleted/Removed.kt"],
        "json",
    );
    let requests = backend.join().expect("fake diagnostics backend");
    let document = decode_json(&output);
    let expected = missing.display().to_string();

    assert!(!output.status.success(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
        "{document:#}",
    );
    assert_eq!(
        requests[2]["params"]["filePaths"],
        json!([expected]),
        "refresh request: {:#}",
        requests[2],
    );
    assert_eq!(
        document["error"]["details"]["result"]["fileStatuses"][0]["filePath"],
        expected,
    );
}

#[test]
fn relative_escape_is_rejected_before_runtime_resolution() {
    assert_pre_dispatch_path_error("../Outside.kt", "AGENT_FILE_OUTSIDE_WORKSPACE");
}

#[test]
fn unsupported_file_kind_is_rejected_before_runtime_resolution() {
    assert_pre_dispatch_path_error("src/App.java", "AGENT_FILE_KIND_UNSUPPORTED");
}

#[test]
fn incomplete_semantic_analysis_fails_closed_in_every_output_format() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Missing.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");

    let socket_path = workspace_socket_path(&workspace, temp.path());
    let listener = bind_listener(&socket_path);
    write_descriptor(&home, &workspace, &socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh(&canonical_test_path(&file)),
        incomplete_diagnostics(&canonical_test_path(&file)),
        12,
    );

    let json_output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let human_output = run_diagnostics(&home, &config_home, &workspace, &file, "human");
    let toon_output = run_diagnostics(&home, &config_home, &workspace, &file, "toon");
    let requests = backend.join().expect("fake diagnostics backend");

    assert_eq!(
        request_methods(&requests),
        expected_diagnostics_methods().repeat(3),
    );
    for (format, output, document) in [
        ("json", &json_output, decode_json(&json_output)),
        ("human", &human_output, decode_json(&human_output)),
        ("toon", &toon_output, decode_toon(&toon_output)),
    ] {
        assert!(
            !output.status.success(),
            "{format} diagnostics must fail closed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        assert_eq!(document["ok"], false, "{format}: {document:#}");
        assert!(document["result"].is_null(), "{format}: {document:#}");
        assert_eq!(
            document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
            "{format}: {document:#}",
        );
        assert_semantic_counts(&document, "INCOMPLETE", 1, 0, 1, format);
    }
}

#[test]
fn incomplete_semantic_admission_stops_before_diagnostics() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Pending.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, "fun pending(): Int = 42\n").expect("scenario source");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");

    let socket_path = workspace_socket_path(&workspace, temp.path());
    let listener = bind_listener(&socket_path);
    write_descriptor(&home, &workspace, &socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        incomplete_refresh(&canonical_test_path(&file)),
        complete_clean_diagnostics(&canonical_test_path(&file)),
        3,
    );

    let output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let requests = backend.join().expect("fake diagnostics backend");
    let document = decode_json(&output);

    assert_eq!(
        request_methods(&requests),
        ["runtime/status", "capabilities", "raw/workspace-refresh"],
    );
    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["ok"], false, "{document:#}");
    assert!(document["result"].is_null(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
        "{document:#}",
    );
    assert_semantic_counts(&document, "INCOMPLETE", 1, 0, 1, "json");
}

#[test]
fn ordinary_compiler_diagnostic_remains_a_successful_complete_analysis() {
    let (output, methods) = run_single_json_scenario(
        "Broken.kt",
        "fun broken(): Int = \"nope\"\n",
        complete_compiler_diagnostics,
    );
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(
        output.status.success(),
        "ordinary compiler diagnostics retain successful semantic analysis: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(document["ok"], true, "{document:#}");
    assert_eq!(document["result"]["ok"], true, "{document:#}");
    assert_eq!(
        document["result"]["severityCounts"]["error"], 1,
        "{document:#}"
    );
    assert_semantic_counts(&document, "COMPLETE", 1, 1, 0, "json");
}

#[test]
fn clean_file_remains_a_successful_complete_analysis() {
    let (output, methods) = run_single_json_scenario(
        "Clean.kt",
        "fun clean(): Int = 42\n",
        complete_clean_diagnostics,
    );
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(
        output.status.success(),
        "clean analysis should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(document["ok"], true, "{document:#}");
    assert_semantic_counts(&document, "COMPLETE", 1, 1, 0, "json");
}
