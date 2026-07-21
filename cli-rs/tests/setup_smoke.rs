mod support;

use std::process::Stdio;
use support::*;

fn setup_command(home: &Path, kast_home: &Path, source: &Path) -> Command {
    let mut command = kast(home, &kast_home.join("unused-config"));
    command
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", kast_home)
        .args([
            "--output",
            "json",
            "setup",
            "--source",
            source.to_str().expect("bundle source"),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

fn setup(home: &Path, kast_home: &Path, source: &Path) -> std::process::Output {
    setup_command(home, kast_home, source)
        .output()
        .expect("kast setup")
}

#[test]
fn setup_activates_one_validated_release_and_converges_on_rerun() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(kast_home.join("current")).expect("legacy current");
    std::fs::write(kast_home.join("current/junk"), "legacy").expect("legacy current file");
    std::fs::write(kast_home.join("install.json"), "legacy").expect("legacy manifest");
    std::fs::create_dir_all(home.join(".local/bin")).expect("bin");
    std::fs::write(home.join(".local/bin/kast"), "legacy").expect("legacy command");

    let first = setup(&home, &kast_home, &source);
    assert!(
        first.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let first: serde_json::Value = serde_json::from_slice(&first.stdout).expect("setup JSON");
    assert_eq!(first["type"], "KAST_SETUP");
    assert_eq!(first["status"], "ACTIVATED");
    assert_eq!(first["verified"], true);
    let release_digest = first["releaseDigest"].as_str().expect("release digest");
    assert_eq!(release_digest.len(), 64);
    let release = kast_home.join("releases").join(release_digest);
    assert!(release.join("manifest.json").is_file());
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("current release"),
        std::fs::canonicalize(&release).expect("release"),
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current/bin/kast")).expect("active command"),
        std::fs::canonicalize(release.join("bin/kast")).expect("active binary"),
    );
    assert!(!home.join(".local/bin/kast").exists());
    assert!(!kast_home.join("install.json").exists());
    assert!(!home.join(".config/kast").exists());

    let second = setup(&home, &kast_home, &source);
    assert!(
        second.status.success(),
        "repeated setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
    );
    let second: serde_json::Value = serde_json::from_slice(&second.stdout).expect("setup JSON");
    assert_eq!(second["status"], "CURRENT");
    assert_eq!(second["releaseDigest"], release_digest);
    assert_eq!(second["verified"], true);

    std::fs::write(kast_home.join("staging/junk"), "stale").expect("stale staging");
    let third = setup(&home, &kast_home, &source);
    assert!(third.status.success());
    assert!(!kast_home.join("staging/junk").exists());
}

#[test]
fn setup_rolls_back_when_the_new_release_fails_readiness() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    std::fs::create_dir_all(&home).expect("home");
    let good = write_install_bundle_source(temp.path(), "v1.0.0");
    let first = setup(&home, &kast_home, &good);
    assert!(first.status.success());
    let first: serde_json::Value = serde_json::from_slice(&first.stdout).expect("setup JSON");
    let active = std::fs::canonicalize(kast_home.join("current")).expect("active release");

    let broken = write_install_bundle_source(temp.path(), "v2.0.0");
    std::fs::write(broken.join("bin/kast"), "#!/bin/sh\nexit 1\n").expect("broken CLI");
    set_executable_for_test(&broken.join("bin/kast"));
    let manifest_path = broken.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).expect("bundle manifest"))
            .expect("manifest JSON");
    manifest["artifacts"][0]["sha256"] =
        serde_json::Value::String(test_path_sha256(&broken.join("bin/kast")));
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).expect("manifest JSON"),
    )
    .expect("updated manifest");
    let failed = setup(&home, &kast_home, &broken);
    assert!(!failed.status.success(), "broken release must fail setup");
    let failed: serde_json::Value = serde_json::from_slice(&failed.stdout).expect("failure JSON");
    assert_eq!(failed["code"], "SETUP_VERIFY_FAILED");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("rolled-back release"),
        active,
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current/bin/kast")).expect("active command"),
        active.join("bin/kast"),
    );
    assert_eq!(
        first["releaseDigest"],
        std::fs::read_link(kast_home.join("previous"))
            .expect("previous release")
            .file_name()
            .and_then(|name| name.to_str())
            .expect("previous digest"),
    );
}

#[test]
fn concurrent_setup_serializes_on_one_release() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v3.0.0");
    std::fs::create_dir_all(&home).expect("home");

    let first = setup_command(&home, &kast_home, &source)
        .spawn()
        .expect("first setup");
    let second = setup_command(&home, &kast_home, &source)
        .spawn()
        .expect("second setup");
    let first = first.wait_with_output().expect("first output");
    let second = second.wait_with_output().expect("second output");
    assert!(
        first.status.success(),
        "first setup: {}",
        String::from_utf8_lossy(&first.stdout)
    );
    assert!(
        second.status.success(),
        "second setup: {}",
        String::from_utf8_lossy(&second.stdout)
    );
    let statuses = [first, second]
        .map(|output| {
            serde_json::from_slice::<serde_json::Value>(&output.stdout).expect("setup JSON")
        })
        .map(|value| value["status"].as_str().expect("status").to_string());
    assert!(statuses.contains(&"ACTIVATED".to_string()), "{statuses:?}");
    assert!(statuses.contains(&"CURRENT".to_string()), "{statuses:?}");
}

#[test]
fn setup_rejects_a_modified_artifact_before_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    std::fs::create_dir_all(&home).expect("home");
    let good = write_install_bundle_source(temp.path(), "v4.0.0");
    assert!(setup(&home, &kast_home, &good).status.success());
    let active = std::fs::canonicalize(kast_home.join("current")).expect("active release");

    let modified = write_install_bundle_source(temp.path(), "v4.1.0");
    std::fs::write(modified.join("skills/kast/SKILL.md"), "modified").expect("drift");
    let rejected = setup(&home, &kast_home, &modified);
    assert!(!rejected.status.success());
    let rejected: serde_json::Value = serde_json::from_slice(&rejected.stdout).expect("error JSON");
    assert_eq!(rejected["code"], "BUNDLE_ARTIFACT_MISMATCH");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("unchanged release"),
        active,
    );
}

#[test]
fn setup_is_the_only_public_installation_mutator() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let root_help = kast(&home, &config)
        .arg("--help")
        .output()
        .expect("root help");
    assert!(root_help.status.success());
    let root_help = String::from_utf8_lossy(&root_help.stdout);
    assert!(root_help.contains("\n  setup "), "{root_help}");
    for retired in ["\n  repair ", "\n  machine "] {
        assert!(
            !root_help.contains(retired),
            "retired command remains: {root_help}"
        );
    }

    let setup_help = kast(&home, &config)
        .args(["setup", "--help"])
        .output()
        .expect("setup help");
    assert!(setup_help.status.success());
    let setup_help = String::from_utf8_lossy(&setup_help.stdout);
    assert!(setup_help.contains("--source"), "{setup_help}");
    for retired in ["--workspace-root", "--force", "--dry-run"] {
        assert!(
            !setup_help.contains(retired),
            "retired setup option remains: {setup_help}"
        );
    }

    let release_help = kast(&home, &config)
        .args(["developer", "release", "--help"])
        .output()
        .expect("release help");
    assert!(release_help.status.success());
    assert!(
        !String::from_utf8_lossy(&release_help.stdout).contains("\n  activate "),
        "direct bundle activation remains public",
    );
}
