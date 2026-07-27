#[test]
fn path_filters_are_normalized_and_workspace_relative() {
    let stdout = assert_typed_boundary(&["--path-prefix", "./src/main", "--glob", "src/**/*.kt"]);
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["pathPrefix"], "src/main",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["glob"], "src/**/*.kt",
        "{stdout:#}"
    );

    for rejected in [
        "/absolute",
        "../outside",
        "src/../outside",
        "C:/workspace/src",
        "C:workspace/src",
        "C:\\workspace\\src",
        "//server/share/src",
        "\\\\server\\share\\src",
        "",
    ] {
        assert_usage_error(&["--path-prefix", rejected]);
    }
    for rejected in [
        "regex:.*\\.kt",
        "/**/*.kt",
        "../**/*.kt",
        "C:/workspace/**/*.kt",
        "C:workspace/**/*.kt",
        "C:\\workspace\\**\\*.kt",
        "//server/share/**/*.kt",
        "\\\\server\\share\\**\\*.kt",
        "",
    ] {
        assert_usage_error(&["--glob", rejected]);
    }
}
