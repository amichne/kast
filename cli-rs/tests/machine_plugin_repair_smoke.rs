mod support;

use support::*;

#[test]
fn idea_plugin_install_uses_profile_install_mode() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("AndroidStudio2026.2")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "--output",
            "json",
            "machine",
            "plugin",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
            "--dry-run",
        ])
        .output()
        .expect("install idea plugin");

    assert!(
        install.status.success(),
        "link mode should plan cask install: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "install");
    assert_eq!(stdout["brewCommand"][1], "install");
    assert_eq!(stdout["brewCommand"][2], "--cask");
    assert_eq!(stdout["pluginVersion"], "9.8.7");
    assert_eq!(
        stdout["downloadCache"],
        home.join("000--kast-plugin.zip").display().to_string()
    );
    assert_eq!(stdout["downloadedBytes"], 0);
    assert_eq!(
        stdout["jetbrainsConfigRoot"],
        jetbrains_root.display().to_string()
    );
    assert_eq!(
        stdout["pluginDirectories"][0],
        jetbrains_root
            .join("AndroidStudio2026.2/plugins")
            .display()
            .to_string()
    );
    assert!(stdout.get("downloadDir").is_none(), "{stdout}");
}

#[test]
fn plugin_install_leaves_install_owned_config_to_doctor_repair() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    let install_root = home.join(".local/share/kast");
    let stale_bin = temp.path().join("stale-bin");
    let stale_runtime_libs = temp.path().join("runtime-libs");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    std::fs::write(stale_bin.join("kast"), b"old binary\n").expect("old binary");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"
runtimeDir = "{}"

[backends.headless]
runtimeLibsDir = "{}"
ideaHome = "{}"

[cli]
binaryPath = "{}"

[install]
components = []
installedAt = "unix:1"
managedPaths = []
platform = "macos-aarch64"
schemaVersion = 3
version = "0.7.35"
"#,
            install_root.display(),
            install_root.join("runtime").display(),
            stale_runtime_libs.display(),
            temp.path().join("idea").display(),
            stale_bin.join("kast").display(),
        ),
    )
    .expect("config");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "--output",
            "json",
            "machine",
            "plugin",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("install plugin");

    assert!(
        install.status.success(),
        "plugin install should perform only scoped plugin work: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "install");
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after install");
    assert!(config_after.contains("[paths]"));
    assert!(config_after.contains("runtimeLibsDir"));
    assert!(config_after.contains("[install]"));
    assert!(config_after.contains("binaryPath"));
}
