use super::*;

#[test]
fn home_reports_live_workspace_state_without_protocol_cruft() {
    let state = tempfile::tempdir().expect("temporary state");
    let kast_home = state.path().join("kast home with spaces");
    let developer_cli = kast_home.join("current/libexec/kastctl");
    std::fs::create_dir_all(developer_cli.parent().expect("developer CLI parent"))
        .expect("developer CLI directory");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &developer_cli).expect("developer CLI fixture");
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");
    let output = named("kast")
        .current_dir(workspace)
        .env("KAST_HOME", &kast_home)
        .env("XDG_CONFIG_HOME", state.path().join("config"))
        .output()
        .expect("run kast home");

    assert!(output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("root:"), "{stdout}");
    assert!(stdout.contains("ready:"), "{stdout}");
    assert!(stdout.contains("referenceIndexReady:"), "{stdout}");
    assert!(stdout.contains("next["), "{stdout}");
    let decoded: serde_json::Value =
        toon_format::decode_default(stdout.trim()).expect("home output is valid TOON");
    assert_eq!(
        decoded["developerOperations"]["cli"],
        developer_cli.display().to_string()
    );
    assert_eq!(
        decoded["developerOperations"]["helpArgs"],
        serde_json::json!(["--help"]),
    );
    assert_eq!(decoded["developerOperations"]["skill"], "/kast:developer");
    let help_args = decoded["developerOperations"]["helpArgs"]
        .as_array()
        .expect("developer help args")
        .iter()
        .map(|argument| argument.as_str().expect("developer help argument"));
    let help = Command::new(&developer_cli)
        .args(help_args)
        .output()
        .expect("run routed developer help");
    assert!(help.status.success(), "{help:?}");
    assert!(
        String::from_utf8_lossy(&help.stdout).contains("Usage: kastctl"),
        "{help:?}"
    );
    for cruft in ["state: UNKNOWN", "schemaVersion", "ok:", "method:"] {
        assert!(!stdout.contains(cruft), "leaked {cruft}: {stdout}");
    }
}

#[test]
fn home_rejects_an_invalid_install_receipt_instead_of_fabricating_a_developer_route() {
    let state = tempfile::tempdir().expect("temporary state");
    let kast_home = state.path().join("kast");
    std::fs::create_dir_all(kast_home.join("current")).expect("current install directory");
    std::fs::write(kast_home.join("current/receipt.json"), "{not-json")
        .expect("invalid install receipt");
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");

    let output = named("kast")
        .current_dir(workspace)
        .env("KAST_HOME", &kast_home)
        .env("XDG_CONFIG_HOME", state.path().join("config"))
        .output()
        .expect("run kast home");

    assert!(!output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("INSTALL_MANIFEST_INVALID"), "{stdout}");
    assert!(!stdout.contains("developerOperations"), "{stdout}");
}

#[test]
fn home_rejects_a_receipt_whose_developer_route_is_not_the_control_entrypoint() {
    let state = tempfile::tempdir().expect("temporary state");
    let home = state.path().join("home");
    let config_home = state.path().join("config");
    write_current_cli_install_manifest_for_test(&home, &config_home);
    let kast_home = home.join(".local/share/kast");
    let receipt_path = kast_home.join("current/receipt.json");
    let mut receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("install receipt"))
            .expect("install receipt JSON");
    receipt["entrypoints"]["activeBinary"] =
        serde_json::json!(kast_home.join("current/bin/kast").display().to_string());
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("install receipt JSON"),
    )
    .expect("updated install receipt");
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");

    let output = named("kast")
        .current_dir(workspace)
        .env("HOME", &home)
        .env("KAST_HOME", &kast_home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("run kast home");

    assert!(!output.status.success(), "{output:?}");
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("DEVELOPER_OPERATIONS_ROUTE_INVALID"),
        "{stdout}"
    );
    assert!(!stdout.contains("developerOperations"), "{stdout}");
}
