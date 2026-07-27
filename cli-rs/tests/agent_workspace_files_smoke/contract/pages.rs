#[test]
fn public_page_tokens_are_canonical_and_file_view_bound() {
    let canonical = "123e4567-e89b-42d3-a456-426614174000";
    let stdout = assert_typed_boundary(&["--page-token", canonical]);
    assert_eq!(
        stdout["error"]["details"]["pageHandle"]["token"], canonical,
        "{stdout:#}"
    );
    assert!(
        stdout["error"]["details"]["admittedQuery"]
            .get("pageHandle")
            .is_none(),
        "{stdout:#}"
    );

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
