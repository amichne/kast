mod support;

use support::*;

fn run_workspace_files(extra_args: &[&str]) -> std::process::Output {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    kast(&home, &config_home)
        .args(["--output", "json", "agent", "workspace-files"])
        .args(extra_args)
        .output()
        .expect("workspace-files command")
}

fn assert_typed_boundary(extra_args: &[&str]) -> serde_json::Value {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON error");
    assert_eq!(
        stdout["error"]["code"], "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "{stdout:#}"
    );
    stdout
}

fn assert_usage_error(extra_args: &[&str]) {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(2),
        "args={extra_args:?} stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files usage JSON");
    assert_eq!(stdout["code"], "CLI_USAGE", "{stdout:#}");
}

#[test]
fn workspace_files_is_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = kast(&home, &config_home)
        .args(["agent", "workspace-files", "--help"])
        .output()
        .expect("workspace-files help");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

#[test]
fn documented_workspace_file_arguments_reach_the_typed_boundary() {
    let stdout = assert_typed_boundary(&[
        "--workspace-root",
        "/workspace",
        "--backend",
        "idea",
        "--module",
        "gradle:included/tools#:app",
        "--source-set",
        "integrationTest",
        "--kind",
        "source",
        "--package",
        "named:例子.`when`",
        "--dirty",
        "dirty",
        "--drift",
        "not-applicable",
        "--path-prefix",
        "src/main",
        "--glob",
        "**/*.kt",
        "--limit",
        "200",
        "--page-token",
        "123e4567-e89b-42d3-a456-426614174000",
        "--fields",
        "path,evidence",
    ]);
    assert_eq!(
        stdout["error"]["details"]["normalizedQuery"]["package"], "named:例子.`when`",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["normalizedQuery"]["packageName"], "例子.when",
        "{stdout:#}"
    );
}

#[test]
fn module_selectors_are_closed_and_build_qualified() {
    for accepted in [
        "backend:kast.analysis-api.main",
        "gradle:.#:app",
        "gradle:included/tools#:app",
    ] {
        let stdout = assert_typed_boundary(&["--module", accepted]);
        assert_eq!(
            stdout["error"]["details"]["normalizedQuery"]["module"], accepted,
            "{stdout:#}"
        );
    }

    for rejected in [
        "analysis-api",
        "backend:",
        "gradle:/absolute#:app",
        "gradle:../outside#:app",
        "gradle:included/tools#app",
    ] {
        assert_usage_error(&["--module", rejected]);
    }
}

#[test]
fn package_selectors_normalize_kotlin_semantic_names() {
    for (accepted, canonical) in [
        ("root", "root"),
        ("named:com.example", "named:com.example"),
        ("named:com.example.`when`", "named:com.example.`when`"),
        ("named:com.`non-identifier`", "named:com.`non-identifier`"),
        ("named:例子.工具", "named:例子.工具"),
    ] {
        let stdout = assert_typed_boundary(&["--package", accepted]);
        assert_eq!(
            stdout["error"]["details"]["normalizedQuery"]["package"], canonical,
            "{stdout:#}"
        );
        assert_typed_boundary(&["--package", canonical]);
    }

    for rejected in [
        "com.example",
        "named:",
        "named:com..example",
        "named:com.`unterminated",
        "named:com.non-identifier",
        "named:com.when",
        "named:com.`bad:name`",
        "named:com.`bad[name]`",
    ] {
        assert_usage_error(&["--package", rejected]);
    }
}

#[test]
fn path_filters_are_normalized_and_workspace_relative() {
    let stdout = assert_typed_boundary(&["--path-prefix", "./src/main", "--glob", "src/**/*.kt"]);
    assert_eq!(
        stdout["error"]["details"]["normalizedQuery"]["pathPrefix"], "src/main",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["normalizedQuery"]["glob"], "src/**/*.kt",
        "{stdout:#}"
    );

    for rejected in ["/absolute", "../outside", "src/../outside", ""] {
        assert_usage_error(&["--path-prefix", rejected]);
    }
    for rejected in ["regex:.*\\.kt", "/**/*.kt", "../**/*.kt", ""] {
        assert_usage_error(&["--glob", rejected]);
    }
}

#[test]
fn workspace_globs_have_explicit_resource_bounds() {
    let max_bytes = "a".repeat(512);
    assert_typed_boundary(&["--glob", &max_bytes]);
    let over_max_bytes = "a".repeat(513);
    assert_usage_error(&["--glob", &over_max_bytes]);

    let max_segments = vec!["a"; 32].join("/");
    assert_typed_boundary(&["--glob", &max_segments]);
    let over_max_segments = vec!["a"; 33].join("/");
    assert_usage_error(&["--glob", &over_max_segments]);

    let max_metacharacters = "?".repeat(64);
    assert_typed_boundary(&["--glob", &max_metacharacters]);
    let over_max_metacharacters = "?".repeat(65);
    assert_usage_error(&["--glob", &over_max_metacharacters]);
}

#[test]
fn workspace_file_limit_is_typed_and_bounded() {
    let default = assert_typed_boundary(&[]);
    assert_eq!(
        default["error"]["details"]["normalizedQuery"]["limit"], 20,
        "{default:#}"
    );
    for accepted in ["1", "200"] {
        assert_typed_boundary(&["--limit", accepted]);
    }
    for rejected in ["0", "201", "not-a-number"] {
        assert_usage_error(&["--limit", rejected]);
    }
}

#[test]
fn public_page_tokens_are_canonical_and_file_view_bound() {
    let canonical = "123e4567-e89b-42d3-a456-426614174000";
    assert_typed_boundary(&["--page-token", canonical]);

    for rejected in [
        "",
        "123e4567e89b42d3a456426614174000",
        "123E4567-E89B-42D3-A456-426614174000",
        "123e4567-e89b-12d3-a456-426614174000",
        "00000000-0000-0000-0000-000000000000",
    ] {
        assert_usage_error(&["--page-token", rejected]);
    }
    assert_usage_error(&["--page-token", canonical, "--count"]);
}

#[test]
fn workspace_file_result_views_are_family_specific_and_exclusive() {
    for accepted in [
        vec!["--verbose"],
        vec!["--explain"],
        vec!["--count"],
        vec![
            "--fields",
            "path,module,source-set,kind,package,index,drift,dirty,evidence",
        ],
    ] {
        assert_typed_boundary(&accepted);
    }

    for rejected in [
        vec!["--verbose", "--explain"],
        vec!["--fields", "path", "--count"],
        vec!["--fields", "identity"],
    ] {
        assert_usage_error(&rejected);
    }
}

#[test]
fn source_set_names_are_typed_without_directory_assumptions() {
    let stdout = assert_typed_boundary(&["--source-set", "integrationTest"]);
    assert_eq!(
        stdout["error"]["details"]["normalizedQuery"]["sourceSet"], "integrationTest",
        "{stdout:#}"
    );
    for rejected in ["", "src/integrationTest", ":integrationTest"] {
        assert_usage_error(&["--source-set", rejected]);
    }
}

#[test]
fn kind_filter_derives_a_closed_collection_domain() {
    for (arguments, expected) in [
        (vec![], "mixed"),
        (vec!["--kind", "source"], "source-only"),
        (vec!["--kind", "script"], "script-only"),
    ] {
        let stdout = assert_typed_boundary(&arguments);
        assert_eq!(
            stdout["error"]["details"]["normalizedQuery"]["kindDomain"], expected,
            "{stdout:#}"
        );
    }
}
