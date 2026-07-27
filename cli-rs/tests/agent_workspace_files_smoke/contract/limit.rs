#[test]
fn workspace_file_limit_is_typed_and_bounded() {
    let default = assert_typed_boundary(&[]);
    assert_eq!(
        default["error"]["details"]["admittedQuery"]["limit"], 20,
        "{default:#}"
    );
    for accepted in ["1", "200"] {
        assert_typed_boundary(&["--limit", accepted]);
    }
    for rejected in ["0", "201", "not-a-number"] {
        assert_usage_error(&["--limit", rejected]);
    }
}
