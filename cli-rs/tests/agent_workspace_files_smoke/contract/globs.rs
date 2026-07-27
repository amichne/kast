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
