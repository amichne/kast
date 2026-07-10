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
            "developer",
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
    assert_eq!(stdout["pluginVersion"], env!("CARGO_PKG_VERSION"));
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
    assert_eq!(stdout["developerDefaults"]["defaultBackend"], "idea");
    assert_eq!(stdout["developerDefaults"]["applied"], false);
    assert!(
        !config_home.join("config.toml").exists(),
        "dry-run plugin install must not write developer defaults"
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
            "developer",
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

#[cfg(unix)]
#[test]
fn repair_does_not_relink_plugins_while_a_jetbrains_ide_is_running() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    let plugin_dir = jetbrains_root.join("IntelliJIdea2026.1/plugins");
    let stale_target = temp.path().join("stale-kast-plugin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&plugin_dir).expect("plugin dir");
    std::fs::create_dir_all(&stale_target).expect("stale target");
    std::os::unix::fs::symlink(&stale_target, plugin_dir.join("kast")).expect("stale link");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);
    write_macos_homebrew_receipt_for_test(&home, Path::new(env!("CARGO_BIN_EXE_kast")));

    let repair = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_PS_JETBRAINS", "IntelliJ IDEA")
        .env("KAST_FAKE_BREW_CASK_VERSION", env!("CARGO_PKG_VERSION"))
        .args([
            "--output",
            "json",
            "repair",
            "--for",
            "machine",
            "--apply",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("repair");

    assert!(!repair.status.success(), "running IDE should block relink");
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("error json");
    assert_eq!(stdout["code"], "JETBRAINS_IDE_RUNNING", "{stdout}");
    assert_eq!(
        std::fs::read_link(plugin_dir.join("kast")).expect("unchanged link"),
        stale_target
    );
}
