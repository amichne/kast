fn authored_version(path: &str, content: &str) -> u32 {
    content
        .trim()
        .parse::<u32>()
        .unwrap_or_else(|error| panic!("{path} must contain a positive integer: {error}"))
}

#[test]
fn authored_protocol_schema_versions_match_rust_build_script() {
    let api = authored_version(
        "api-schema-version.txt",
        include_str!("../protocol/api-schema-version.txt"),
    );
    let generated_api =
        authored_version("KAST_API_SCHEMA_VERSION", env!("KAST_API_SCHEMA_VERSION"));
    let install_receipt = authored_version(
        "install-receipt-schema-version.txt",
        include_str!("../protocol/install-receipt-schema-version.txt"),
    );
    let generated_install_receipt = authored_version(
        "KAST_INSTALL_RECEIPT_SCHEMA_VERSION",
        env!("KAST_INSTALL_RECEIPT_SCHEMA_VERSION"),
    );

    assert!(api > 0);
    assert!(install_receipt > 0);
    assert_eq!(api, generated_api);
    assert_eq!(install_receipt, generated_install_receipt);
}
