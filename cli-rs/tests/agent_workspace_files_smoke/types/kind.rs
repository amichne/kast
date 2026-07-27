#[test]
fn kind_filter_derives_a_closed_collection_domain() {
    for (arguments, expected) in [
        (vec![], "mixed"),
        (vec!["--kind", "source"], "source-only"),
        (vec!["--kind", "script"], "script-only"),
    ] {
        let stdout = assert_typed_boundary(&arguments);
        assert_eq!(
            stdout["error"]["details"]["admittedQuery"]["kindDomain"], expected,
            "{stdout:#}"
        );
        let filters = stdout["error"]["details"]["admittedQuery"]["filters"]
            .as_object()
            .expect("typed filters");
        if arguments.is_empty() {
            assert!(!filters.contains_key("kind"), "{stdout:#}");
        }
    }
}
