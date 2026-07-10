mod support;

use support::*;

#[test]
fn idea_plugin_install_requires_jetbrains_profiles_in_normalized_install_path() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    std::fs::create_dir_all(&home).expect("home");
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
            "--dry-run",
        ])
        .output()
        .expect("install idea plugin");

    assert!(
        !install.status.success(),
        "default install should require JetBrains profiles instead of downloading a zip: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stdout = String::from_utf8_lossy(&install.stdout);
    assert!(stdout.contains("JETBRAINS_CONFIG_NOT_FOUND"), "{stdout}");
    assert!(
        !home.join("Downloads/kast-plugin.zip").exists(),
        "normalized plugin install must not create a manual plugin zip"
    );
}

#[test]
fn plugin_install_gateway_installs_homebrew_cask_and_links_profiles() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
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
        "plugin gateway should install the Homebrew cask: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "install");
    assert_eq!(stdout["brewCommand"][1], "install");
    assert_eq!(stdout["brewCommand"][2], "--cask");
    assert_eq!(stdout["brewCommand"][3], "amichne/kast/kast-plugin");
    assert_eq!(
        stdout["jetbrainsConfigRoot"],
        jetbrains_root.display().to_string()
    );
    assert_eq!(
        stdout["pluginDirectories"][0],
        jetbrains_root
            .join("IntelliJIdea2026.1/plugins")
            .display()
            .to_string()
    );
    assert!(stdout.get("downloadDir").is_none(), "{stdout}");
    assert!(stdout.get("downloadedPath").is_none(), "{stdout}");
    assert_eq!(stdout["developerDefaults"]["defaultBackend"], "idea");
    assert_eq!(stdout["developerDefaults"]["ideaLaunchEnabled"], true);
    assert_eq!(stdout["developerDefaults"]["applied"], true);
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("defaultBackend = \"idea\""), "{config}");
    assert!(config.contains("[runtime.ideaLaunch]"), "{config}");
    assert!(config.contains("enabled = true"), "{config}");
    assert!(config.contains("command = \"idea\""), "{config}");
    assert!(config.contains("requireInstalledPlugin = true"), "{config}");
    #[cfg(unix)]
    assert_eq!(
        std::fs::read_link(jetbrains_root.join("IntelliJIdea2026.1/plugins/kast"))
            .expect("plugin symlink"),
        PathBuf::from(format!(
            "/opt/homebrew/Caskroom/kast-plugin/{}/backend-idea",
            env!("CARGO_PKG_VERSION")
        ))
    );
}

#[test]
fn plugin_install_writes_homebrew_authority_receipt_after_success() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
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
        "plugin install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let receipt_path = home.join("Library/Application Support/Kast/homebrew-install.json");
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(
        stdout["homebrewReceipt"],
        receipt_path.display().to_string(),
        "{stdout}"
    );
    let receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("Homebrew install receipt"))
            .expect("receipt json");
    assert_eq!(receipt["authority"], "macos-homebrew");
    assert_eq!(receipt["cli"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(receipt["plugin"]["version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(receipt["plugin"]["caskToken"], "amichne/kast/kast-plugin");
}

#[test]
fn plugin_install_skips_homebrew_when_matching_cask_is_installed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_BREW_CASK_VERSION", env!("CARGO_PKG_VERSION"))
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
        "matching plugin install should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "none", "{stdout}");
    assert_eq!(stdout["brewCommand"], serde_json::json!([]), "{stdout}");
}

#[test]
fn plugin_install_refuses_mutation_while_intellij_is_running() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_PS_JETBRAINS", "IntelliJ IDEA")
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

    assert!(!install.status.success(), "running IDEA must block install");
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install error json");
    assert_eq!(stdout["code"], "JETBRAINS_IDE_RUNNING", "{stdout}");
    assert_eq!(stdout["details"]["products"], "IntelliJ IDEA", "{stdout}");
    assert!(!config_home.join("config.toml").exists());
    assert!(
        !home
            .join("Library/Application Support/Kast/homebrew-install.json")
            .exists()
    );
}

#[test]
fn plugin_install_rejects_cask_version_that_differs_from_cli() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_BREW_PLUGIN_VERSION", "9.8.7")
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
        !install.status.success(),
        "mismatched plugin must fail closed"
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install error json");
    assert_eq!(
        stdout["code"], "HOMEBREW_PLUGIN_VERSION_MISMATCH",
        "{stdout}"
    );
    assert!(!config_home.join("config.toml").exists());
    assert!(
        !home
            .join("Library/Application Support/Kast/homebrew-install.json")
            .exists()
    );
}

#[test]
fn plugin_install_human_output_reports_progress_and_summary_tables() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "--output",
            "human",
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
        "plugin install should succeed with human progress: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("Resolving Homebrew-installed Kast"),
        "{stderr}"
    );
    assert!(stderr.contains("Kast IDEA plugin install"), "{stderr}");
    assert!(stderr.contains("Fetching Homebrew cask"), "{stderr}");
    assert!(stderr.contains("Running Homebrew install"), "{stderr}");
    assert!(
        stderr.contains("Linking Kast plugin into 1 JetBrains profile"),
        "{stderr}"
    );
    assert!(stderr.contains("Homebrew install complete"), "{stderr}");

    let stdout = String::from_utf8_lossy(&install.stdout);
    assert!(stdout.contains("Kast IDEA plugin install"), "{stdout}");
    assert!(stdout.contains("Install summary"), "{stdout}");
    assert!(stdout.contains("JetBrains destinations"), "{stdout}");
    assert!(stdout.contains("Homebrew action"), "{stdout}");
    assert!(stdout.contains("Brew command"), "{stdout}");
    assert!(
        stdout.contains("- Homebrew action: install") && stdout.contains("- Brew command:"),
        "captured summary should use compact list rows: {stdout}"
    );
    assert!(
        !stdout
            .lines()
            .any(|line| line.starts_with('+') || line.starts_with('┌') || line.starts_with('└')),
        "captured summary should not use table borders: {stdout}"
    );
    assert!(
        stdout.contains("Open IntelliJ IDEA or Android Studio"),
        "{stdout}"
    );
}

#[test]
fn plugin_install_repairs_stale_homebrew_profile_link() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    let plugins_dir = jetbrains_root.join("IntelliJIdea2026.1/plugins");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&plugins_dir).expect("profile plugins");
    #[cfg(unix)]
    std::os::unix::fs::symlink(
        "/opt/homebrew/Caskroom/kast-plugin/0.7.35/backend-idea",
        plugins_dir.join("kast"),
    )
    .expect("stale plugin symlink");
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
        "plugin install should repair stale Homebrew links: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    #[cfg(unix)]
    assert_eq!(
        std::fs::read_link(plugins_dir.join("kast")).expect("plugin symlink after repair"),
        PathBuf::from(format!(
            "/opt/homebrew/Caskroom/kast-plugin/{}/backend-idea",
            env!("CARGO_PKG_VERSION")
        ))
    );
}
