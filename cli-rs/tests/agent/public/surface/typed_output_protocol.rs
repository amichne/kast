use super::named;

#[test]
fn json_and_toon_encode_the_same_canonical_result() {
    let json = named("kast")
        .args(["--output", "json"])
        .output()
        .expect("JSON public home");
    assert!(json.status.success(), "{json:?}");
    let json: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("canonical JSON public home");

    let toon = named("kast")
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
    assert_eq!(json["schemaVersion"], 2);
    assert_eq!(json["operation"], "workspace.home");
    assert_eq!(json["status"], "complete");
    assert_eq!(json["result"]["type"], "home");
}
