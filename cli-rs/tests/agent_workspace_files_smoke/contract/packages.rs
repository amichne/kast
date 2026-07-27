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
            stdout["error"]["details"]["admittedQuery"]["filters"]["package"], canonical,
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
