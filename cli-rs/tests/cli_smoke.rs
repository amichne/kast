use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn write_fake_brew(bin_dir: &Path, formula_prefix: &Path) -> PathBuf {
    let brew = bin_dir.join("brew");
    std::fs::create_dir_all(bin_dir).expect("brew bin");
    std::fs::write(
        &brew,
        format!(
            r#"#!/bin/sh
set -eu
if [ "$1" = "--prefix" ] && [ "$#" -eq 1 ]; then
  printf '%s\n' "/opt/homebrew"
elif [ "$1" = "--prefix" ] && [ "$2" = "kast" ]; then
  printf '%s\n' "{}"
elif [ "$1" = "info" ] && [ "$2" = "--json=v2" ] && [ "$3" = "kast" ]; then
  printf '%s\n' '{{"formulae":[{{"name":"kast","tap":"amichne/kast"}}],"casks":[]}}'
elif [ "$1" = "list" ] && [ "$2" = "--cask" ]; then
  exit 1
else
  printf 'unexpected brew args:' >&2
  printf ' %s' "$@" >&2
  printf '\n' >&2
  exit 64
fi
"#,
            formula_prefix.display()
        ),
    )
    .expect("brew script");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = std::fs::metadata(&brew)
            .expect("brew metadata")
            .permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&brew, permissions).expect("brew mode");
    }
    brew
}

fn write_backend_archive(root: &Path, backend: &str, version: &str) -> PathBuf {
    let staging = root.join(format!("{backend}-staging"));
    let archive = root.join(format!("{backend}.zip"));
    let archive_root = match backend {
        "standalone" => "backend-standalone",
        "headless" => "backend-headless",
        other => panic!("unsupported backend fixture: {other}"),
    };
    let runtime_libs = staging.join(archive_root).join("runtime-libs");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::write(runtime_libs.join("kast-test.jar"), b"fake jar").expect("jar");
    let launcher = staging.join(archive_root).join(format!("kast-{backend}"));
    std::fs::write(&launcher, "#!/bin/sh\n").expect("launcher");
    if backend == "headless" {
        std::fs::create_dir_all(staging.join(archive_root).join("idea-home/lib"))
            .expect("idea lib");
        std::fs::create_dir_all(staging.join(archive_root).join("idea-home/modules"))
            .expect("idea modules");
        std::fs::create_dir_all(
            staging
                .join(archive_root)
                .join("idea-home/plugins/kast-headless"),
        )
        .expect("headless plugin");
        std::fs::write(
            staging.join(archive_root).join("idea-home/lib/nio-fs.jar"),
            b"nio",
        )
        .expect("nio");
        std::fs::write(
            staging
                .join(archive_root)
                .join("idea-home/modules/module-descriptors.dat"),
            b"modules",
        )
        .expect("module descriptors");
    }
    let status = Command::new("zip")
        .args(["-qr", archive.to_str().expect("archive path"), archive_root])
        .current_dir(&staging)
        .status()
        .expect("zip command");
    assert!(
        status.success(),
        "zip command should create fixture archive"
    );
    assert!(archive.is_file(), "archive fixture for {backend} {version}");
    archive
}

#[test]
fn smoke_core_cli_commands() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let version = kast(&home, &config_home)
        .arg("version")
        .output()
        .expect("version");
    assert!(version.status.success());
    assert!(String::from_utf8_lossy(&version.stdout).contains("Kast CLI"));

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    assert!(String::from_utf8_lossy(&help.stdout).contains("Usage: kast"));

    let demo_help = kast(&home, &config_home)
        .args(["demo", "--help"])
        .output()
        .expect("demo help");
    assert!(demo_help.status.success());
    let demo_help_stdout = String::from_utf8_lossy(&demo_help.stdout);
    assert!(demo_help_stdout.contains("symbol-walking demo"));
    assert!(!demo_help_stdout.contains("--no-fallback"));

    let config = kast(&home, &config_home)
        .args(["config", "init"])
        .output()
        .expect("config init");
    assert!(config.status.success());
    assert!(config_home.join("config.toml").is_file());

    let skill_dir = temp.path().join("skills");
    let skill = kast(&home, &config_home)
        .args([
            "install",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "--yes=true",
        ])
        .output()
        .expect("install skill");
    assert!(skill.status.success());
    assert!(skill_dir.join("kast/SKILL.md").is_file());

    let github_dir = temp.path().join("github");
    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot-extension",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--yes=true",
        ])
        .output()
        .expect("install copilot extension");
    assert!(copilot.status.success());
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        github_dir
            .join("extensions/kotlin-gradle-loop/extension.mjs")
            .is_file()
    );

    let status = kast(&home, &config_home)
        .args([
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status");
    assert!(status.status.success());
    assert!(String::from_utf8_lossy(&status.stdout).contains("\"candidates\": []"));
}

#[test]
fn backend_install_standalone_archive_configures_runtime_and_install_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let archive = write_backend_archive(temp.path(), "standalone", "v9.8.7");

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--archive",
            archive.to_str().expect("archive path"),
            "--version",
            "v9.8.7",
        ])
        .output()
        .expect("backend install");

    assert!(
        install.status.success(),
        "backend install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("backend install json");
    assert_eq!(stdout["backendName"], "standalone");
    assert_eq!(stdout["version"], "v9.8.7");
    assert_eq!(stdout["downloaded"], false);
    assert!(
        stdout["runtimeLibsDir"]
            .as_str()
            .unwrap()
            .ends_with(".kast/lib/backends/current/runtime-libs")
    );

    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("[install]"), "{config}");
    assert!(config.contains("[[install.backends]]"), "{config}");
    assert!(config.contains("name = \"standalone\""), "{config}");
    assert!(config.contains("version = \"v9.8.7\""), "{config}");
    assert!(config.contains("\"backend:standalone\""), "{config}");
    assert!(
        home.join(".kast/lib/backends/current/runtime-libs/classpath.txt")
            .is_file()
    );
}

#[test]
fn backend_uninstall_removes_only_the_selected_backend_component() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let standalone = write_backend_archive(temp.path(), "standalone", "v9.8.7");
    let headless = write_backend_archive(temp.path(), "headless", "v9.8.7");

    for (backend, archive) in [("standalone", standalone), ("headless", headless)] {
        let install = kast(&home, &config_home)
            .args([
                "backend",
                "install",
                backend,
                "--archive",
                archive.to_str().expect("archive path"),
                "--version",
                "v9.8.7",
            ])
            .output()
            .expect("backend install");
        assert!(
            install.status.success(),
            "install {backend}: stdout={}, stderr={}",
            String::from_utf8_lossy(&install.stdout),
            String::from_utf8_lossy(&install.stderr)
        );
    }

    let uninstall = kast(&home, &config_home)
        .args(["backend", "uninstall", "standalone"])
        .output()
        .expect("backend uninstall");
    assert!(
        uninstall.status.success(),
        "backend uninstall should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&uninstall.stdout),
        String::from_utf8_lossy(&uninstall.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&uninstall.stdout).expect("backend uninstall json");
    assert_eq!(stdout["backendName"], "standalone");
    assert_eq!(stdout["skipped"], false);

    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(!config.contains("name = \"standalone\""), "{config}");
    assert!(config.contains("name = \"headless\""), "{config}");
    assert!(!home.join(".kast/lib/backends/current").exists());
    assert!(
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .is_file()
    );
}

#[test]
fn up_without_installed_backend_reports_exact_backend_install_command() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let up = kast(&home, &config_home)
        .args([
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(
        stderr.contains("\"code\": \"NO_BACKEND_AVAILABLE\""),
        "{stderr}"
    );
    assert!(
        stderr.contains("kast backend install headless"),
        "stderr should include exact install command: {stderr}"
    );
}

#[test]
fn intellij_plugin_install_defaults_to_downloads_without_jetbrains_profiles() {
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
        .args(["install", "intellij-plugin", "--dry-run"])
        .output()
        .expect("install intellij plugin");

    assert!(
        install.status.success(),
        "default install should not require JetBrains profiles: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "fetch");
    assert_eq!(
        stdout["downloadDir"],
        home.join("Downloads").display().to_string()
    );
    assert_eq!(stdout["brewCommand"][1], "fetch");
    assert_eq!(stdout["brewCommand"][2], "--cask");
    assert_eq!(stdout["brewCommand"][5], "amichne/kast/kast-plugin");
    assert!(stdout.get("jetbrainsConfigRoot").is_none(), "{stdout}");
}

#[test]
fn intellij_plugin_link_flag_uses_profile_install_mode() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.2")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let install = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "install",
            "intellij-plugin",
            "--link-jetbrains-profiles",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
            "--dry-run",
        ])
        .output()
        .expect("install intellij plugin");

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
    assert_eq!(
        stdout["jetbrainsConfigRoot"],
        jetbrains_root.display().to_string()
    );
    assert_eq!(
        stdout["pluginDirectories"][0],
        jetbrains_root
            .join("IntelliJIdea2026.2/plugins")
            .display()
            .to_string()
    );
    assert!(stdout.get("downloadDir").is_none(), "{stdout}");
}

#[test]
fn copilot_extension_install_preserves_existing_github_content() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let github_dir = temp.path().join(".github");
    let workflow = github_dir.join("workflows/ci.yml");
    let instructions = github_dir.join("copilot-instructions.md");
    std::fs::create_dir_all(workflow.parent().expect("workflow parent")).expect("workflow dir");
    std::fs::write(&workflow, b"name: CI\n").expect("workflow");
    std::fs::write(&instructions, b"repo guidance\n").expect("instructions");
    std::fs::write(github_dir.join(".kast-copilot-version"), b"stale\n").expect("marker");

    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot-extension",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--yes=true",
        ])
        .output()
        .expect("install copilot extension");

    assert!(
        copilot.status.success(),
        "install should update packaged resources: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&workflow).expect("workflow"),
        "name: CI\n"
    );
    assert_eq!(
        std::fs::read_to_string(&instructions).expect("instructions"),
        "repo guidance\n"
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(github_dir.join("hooks/hooks.json").is_file());
}

#[test]
fn doctor_resolves_relative_managed_paths_under_install_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = home.join(".kast");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(install_root.join("backends")).expect("backends");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"

[install]
version = "0.1.0"
components = []
managedPaths = ["backends"]
schemaVersion = 3
"#,
            install_root.display()
        ),
    )
    .expect("config");

    let doctor = kast(&home, &config_home)
        .arg("doctor")
        .output()
        .expect("doctor");

    assert!(
        doctor.status.success(),
        "doctor should treat relative managed paths as install-root-relative: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr),
    );
    let stdout = String::from_utf8_lossy(&doctor.stdout);
    assert!(stdout.contains("\"ok\": true"), "{stdout}");
    assert!(!stdout.contains("Managed path is missing"), "{stdout}");
}

#[test]
fn doctor_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = home.join(".kast");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(install_root.join("backends")).expect("backends");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"

[install]
version = "0.1.0"
backendVersion = "0.0.1"
components = ["backend"]
managedPaths = ["backends"]
schemaVersion = 3
"#,
            install_root.display()
        ),
    )
    .expect("config");

    let doctor = kast(&home, &config_home)
        .arg("doctor")
        .output()
        .expect("doctor");
    let stdout = String::from_utf8_lossy(&doctor.stdout);

    assert!(
        !doctor.status.success(),
        "doctor should fail for stale backend"
    );
    assert!(stdout.contains("\"ok\": false"), "{stdout}");
    assert!(stdout.contains("\"minimumBackendVersion\""), "{stdout}");
    assert!(stdout.contains("0.0.1"), "{stdout}");
    assert!(stdout.contains("older than required"), "{stdout}");
}

#[test]
fn archive_install_writes_config_owned_install_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let archive = temp.path().join("kast.zip");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&archive, b"portable archive placeholder").expect("archive");

    let install = kast(&home, &config_home)
        .args([
            "install",
            "--archive",
            archive.to_str().expect("archive path"),
        ])
        .output()
        .expect("install");

    assert!(
        install.status.success(),
        "install should write config state: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("[install]"), "{config}");
    assert!(config.contains("\"cli\""), "{config}");
    assert!(config.contains("\"config\""), "{config}");
    assert!(config.contains("[cli]"), "{config}");
    assert!(config.contains("binaryPath = "), "{config}");
    assert!(!home.join(".kast/.manifest.json").exists());

    let info = kast(&home, &config_home)
        .arg("info")
        .output()
        .expect("info");
    assert!(info.status.success());
    let stdout = String::from_utf8_lossy(&info.stdout);
    assert!(stdout.contains("\"configPath\""), "{stdout}");
    assert!(stdout.contains("\"install\""), "{stdout}");

    let doctor = kast(&home, &config_home)
        .arg("doctor")
        .output()
        .expect("doctor");
    assert!(
        doctor.status.success(),
        "doctor should accept config-owned install state: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr)
    );

    let uninstall = kast(&home, &config_home)
        .arg("uninstall")
        .output()
        .expect("uninstall");
    assert!(
        uninstall.status.success(),
        "uninstall should remove config-owned install state: stdout={}, stderr={}",
        String::from_utf8_lossy(&uninstall.stdout),
        String::from_utf8_lossy(&uninstall.stderr)
    );
    let stdout = String::from_utf8_lossy(&uninstall.stdout);
    assert!(stdout.contains("\"removedInstallState\": true"), "{stdout}");
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(!config.contains("[install]"), "{config}");
    assert!(!home.join(".kast").exists());
}

#[test]
fn packaged_skill_targets_rust_kast_only() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill = std::fs::read_to_string(root.join("resources/kast-skill/SKILL.md"))
        .expect("packaged skill");
    let quickstart =
        std::fs::read_to_string(root.join("resources/kast-skill/references/quickstart.md"))
            .expect("packaged skill quickstart");
    let routing_reference = std::fs::read_to_string(
        root.join("resources/kast-skill/references/routing-improvement.md"),
    )
    .expect("routing reference");
    let routing_builder = std::fs::read_to_string(
        root.join("resources/kast-skill/fixtures/maintenance/scripts/build-routing-corpus.py"),
    )
    .expect("routing builder");

    assert!(skill.contains("Rust `kast` CLI"));
    assert!(skill.contains("command -v kast"));
    assert!(skill.contains("kast metrics fan-in"));
    assert!(skill.contains("kast demo"));
    assert!(skill.contains("raw/type-hierarchy"));
    assert!(skill.contains("raw/semantic-insertion-point"));
    assert!(skill.contains("raw/completions"));
    assert!(skill.contains("raw/apply-edits"));
    assert!(quickstart.contains("command -v kast"));
    assert!(quickstart.contains("kast rpc"));
    assert!(quickstart.contains("kast metrics impact"));
    assert!(quickstart.contains("kast demo"));
    assert!(routing_reference.contains("rust-kast-cli"));
    assert!(routing_builder.contains("\"expected_route\": \"rust-kast-cli\""));
    assert!(routing_builder.contains("kast demo --json"));
    assert!(!skill.contains("JVM CLI"));
    assert!(!skill.contains("Kotlin serialization models"));
    assert!(!skill.contains("KAST_CLI_PATH"));
    assert!(!quickstart.contains("KAST_CLI_PATH"));
    assert!(
        !skill.contains("kast_workspace_")
            && !skill.contains("kast_resolve")
            && !skill.contains("kast_references")
            && !skill.contains("kast_callers")
            && !skill.contains("kast_diagnostics")
            && !skill.contains("kast_rename")
            && !skill.contains("kast_write_and_validate")
            && !skill.contains("kast_metrics"),
        "packaged skill should teach the Rust CLI, not host-specific kast_* tool names",
    );

    for relative in [
        "resources/kast-skill/scripts/resolve-kast.sh",
        "resources/copilot-extension/extensions/kast/scripts/resolve-kast.sh",
        "resources/copilot-extension/hooks/resolve-kast-path.sh",
        "resources/copilot-extension/extensions/kast/extension.mjs",
    ] {
        let content = std::fs::read_to_string(root.join(relative)).expect(relative);
        assert!(
            !content.contains("kast-cli"),
            "{relative} must not resolve or advertise the deleted JVM CLI",
        );
    }
}
