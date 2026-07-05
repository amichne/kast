mod support;

use support::*;

#[test]
fn up_without_installed_backend_reports_supported_headless_distribution() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let up = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
            "--no-auto-start=true",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(stderr.contains("- Code: NO_BACKEND_AVAILABLE"), "{stderr}");
    assert!(
        stderr.contains("Linux headless tarball"),
        "stderr should point to the supported headless distribution: {stderr}"
    );
}

#[test]
fn runtime_commands_use_configured_default_backend_when_backend_flag_is_absent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n",
    )
    .expect("config");

    let up = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--no-auto-start=true",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed headless backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(
        stderr.contains("Linux headless tarball"),
        "stderr should point to the supported headless distribution: {stderr}"
    );
}

#[test]
fn runtime_backend_flag_overrides_configured_default_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n",
    )
    .expect("config");

    let up = kast(&home, &config_home)
        .args([
            "--output",
            "human",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
            "--no-auto-start=true",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed headless backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(
        stderr.contains("Linux headless tarball"),
        "stderr should point to the supported headless distribution: {stderr}"
    );
}

#[test]
fn agent_verify_uses_configured_default_backend_when_auto_starting() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n",
    )
    .expect("config");

    let verify = kast(&home, &config_home)
        .args([
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("agent verify");

    assert!(
        !verify.status.success(),
        "agent verify should fail without an installed headless backend"
    );
    let stdout = String::from_utf8_lossy(&verify.stdout);
    assert!(
        stdout.contains("Linux headless tarball"),
        "agent envelope should point to the supported headless distribution: {stdout}"
    );
}

#[test]
fn agent_verify_backend_flag_overrides_configured_default_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n",
    )
    .expect("config");

    let verify = kast(&home, &config_home)
        .args([
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("agent verify");

    assert!(
        !verify.status.success(),
        "agent verify should fail without an installed headless backend"
    );
    let stdout = String::from_utf8_lossy(&verify.stdout);
    assert!(
        stdout.contains("Linux headless tarball"),
        "agent envelope should point to the supported headless distribution: {stdout}"
    );
}
