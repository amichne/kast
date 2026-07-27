#[test]
fn source_set_names_are_typed_without_directory_assumptions() {
    let stdout = assert_typed_boundary(&["--source-set", "integrationTest"]);
    assert_eq!(
        stdout["error"]["details"]["admittedQuery"]["filters"]["sourceSet"], "integrationTest",
        "{stdout:#}"
    );
    for rejected in ["", "src/integrationTest", ":integrationTest"] {
        assert_usage_error(&["--source-set", rejected]);
    }
}
