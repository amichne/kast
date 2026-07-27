#[test]
fn module_selectors_are_closed_and_build_qualified() {
    for accepted in [
        "backend:kast.analysis-api.main",
        "gradle:.#:app",
        "gradle:included/tools#:app",
    ] {
        let stdout = assert_typed_boundary(&["--module", accepted]);
        assert_eq!(
            stdout["error"]["details"]["admittedQuery"]["filters"]["module"], accepted,
            "{stdout:#}"
        );
    }

    for rejected in [
        "analysis-api",
        "backend:",
        "gradle:/absolute#:app",
        "gradle:../outside#:app",
        "gradle:included/tools#app",
        "gradle:C:/workspace#:app",
        "gradle:C:workspace#:app",
        "gradle:C:\\workspace#:app",
        "gradle://server/share#:app",
        "gradle:\\\\server\\share#:app",
    ] {
        assert_usage_error(&["--module", rejected]);
    }
}
