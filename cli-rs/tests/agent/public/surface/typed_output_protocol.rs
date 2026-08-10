use std::path::Path;

use super::{public_kast_with_install, support::write_current_cli_install_manifest_for_test};

#[test]
fn json_and_toon_encode_the_same_canonical_result() {
    let fixture = tempfile::tempdir().expect("temporary install");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repository root");
    write_current_cli_install_manifest_for_test(&home, &config_home);

    let json = public_kast_with_install(&home, &config_home, workspace)
        .args(["--output", "json"])
        .output()
        .expect("JSON public home");
    assert!(json.status.success(), "{json:?}");
    let json: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("canonical JSON public home");

    let toon = public_kast_with_install(&home, &config_home, workspace)
        .args(["--output", "toon"])
        .output()
        .expect("TOON public home");
    assert!(toon.status.success(), "{toon:?}");
    let toon: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&toon.stdout)
            .expect("UTF-8 TOON public home")
            .trim(),
    )
    .expect("canonical TOON public home");

    assert_eq!(toon, json);
    assert_eq!(json["schemaVersion"], 3);
    assert_eq!(json["operation"], "workspace.home");
    assert_eq!(json["status"], "complete");
    assert_eq!(json["result"]["type"], "home");
}
