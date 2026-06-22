use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::{io::BufRead, io::BufReader, io::Write, os::unix::net::UnixListener, thread};

fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn default_install_root(home: &Path) -> PathBuf {
    home.join(".local/share/kast")
}

fn default_descriptor_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("runtime/daemons")
}

fn default_bin_dir(home: &Path) -> PathBuf {
    home.join(".local/bin")
}

fn install_manifest_path(home: &Path) -> PathBuf {
    default_install_root(home).join("install.json")
}

fn path_report_entry<'a>(report: &'a serde_json::Value, key: &str) -> &'a serde_json::Value {
    report["entries"]
        .as_array()
        .expect("path report entries")
        .iter()
        .find(|entry| entry["key"] == key)
        .unwrap_or_else(|| panic!("missing path report entry {key}: {report:#?}"))
}

fn write_fake_brew(bin_dir: &Path, formula_prefix: &Path) -> PathBuf {
    let brew = bin_dir.join("brew");
    std::fs::create_dir_all(bin_dir).expect("brew bin");
    std::fs::write(
        &brew,
        format!(
            r#"#!/bin/sh
set -eu
state_file="${{HOME:-/tmp}}/.fake-brew-kast-plugin-version"
if [ "$1" = "--prefix" ] && [ "$#" -eq 1 ]; then
  printf '%s\n' "/opt/homebrew"
elif [ "$1" = "--prefix" ] && [ "$2" = "kast" ]; then
  printf '%s\n' "{}"
elif [ "$1" = "info" ] && [ "$2" = "--json=v2" ] && [ "$3" = "kast" ]; then
  printf '%s\n' '{{"formulae":[{{"name":"kast","tap":"amichne/kast"}}],"casks":[]}}'
elif [ "$1" = "info" ] && [ "$2" = "--json=v2" ] && [ "$3" = "--cask" ]; then
  printf '%s\n' '{{"formulae":[],"casks":[{{"token":"kast-plugin","full_token":"amichne/kast/kast-plugin","version":"9.8.7"}}]}}'
elif [ "$1" = "fetch" ] && [ "$2" = "--cask" ]; then
  cache="${{HOME:-/tmp}}/000--kast-plugin.zip"
  printf 'fake plugin zip\n' > "$cache"
  printf 'fake brew fetched kast plugin\n' >&2
elif [ "$1" = "--cache" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{HOME:-/tmp}}/000--kast-plugin.zip"
elif [ "$1" = "install" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{KAST_FAKE_BREW_INSTALL_VERSION:-9.8.7}}" > "$state_file"
  printf 'fake brew installed kast plugin\n' >&2
elif [ "$1" = "reinstall" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{KAST_FAKE_BREW_INSTALL_VERSION:-9.8.7}}" > "$state_file"
  printf 'fake brew reinstalled kast plugin\n' >&2
elif [ "$1" = "list" ] && [ "$2" = "--cask" ]; then
  if [ "${{KAST_FAKE_BREW_CASK_VERSION:-}}" != "" ]; then
    printf 'kast-plugin %s\n' "$KAST_FAKE_BREW_CASK_VERSION"
  elif [ -f "$state_file" ]; then
    read -r installed_version < "$state_file"
    printf 'kast-plugin %s\n' "$installed_version"
  else
    exit 1
  fi
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
    assert_eq!(backend, "headless", "unsupported backend fixture");
    let staging = root.join(format!("{backend}-staging"));
    let archive = root.join(format!("{backend}.zip"));
    let archive_root = "backend-headless";
    let runtime_libs = staging.join(archive_root).join("runtime-libs");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::write(runtime_libs.join("kast-test.jar"), b"fake jar").expect("jar");
    let launcher = staging.join(archive_root).join(format!("kast-{backend}"));
    std::fs::write(&launcher, "#!/bin/sh\n").expect("launcher");
    std::fs::create_dir_all(staging.join(archive_root).join("idea-home/lib")).expect("idea lib");
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

fn write_cli_archive(root: &Path) -> PathBuf {
    let staging = root.join("cli-staging");
    let archive = root.join("kast-cli.zip");
    std::fs::create_dir_all(&staging).expect("cli staging");
    let cli = staging.join("kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &cli).expect("copy test kast binary");
    set_executable_for_test(&cli);
    let status = Command::new("zip")
        .args(["-qr", archive.to_str().expect("archive path"), "kast"])
        .current_dir(&staging)
        .status()
        .expect("zip command");
    assert!(status.success(), "zip command should create CLI fixture");
    assert!(archive.is_file(), "CLI archive fixture");
    archive
}

fn write_install_bundle_source(root: &Path, version: &str) -> PathBuf {
    let platform = "ubuntu-debian-headless-x86_64";
    let bundle = root.join(format!("kast-{platform}-{version}"));
    let backend_dir = bundle.join(format!("lib/backends/headless-{version}"));
    std::fs::create_dir_all(bundle.join("bin")).expect("bundle bin");
    std::fs::create_dir_all(bundle.join("scripts")).expect("bundle scripts");
    std::fs::create_dir_all(backend_dir.join("runtime-libs")).expect("runtime libs");
    std::fs::create_dir_all(backend_dir.join("idea-home/lib")).expect("idea lib");
    std::fs::create_dir_all(backend_dir.join("idea-home/modules")).expect("idea modules");
    std::fs::create_dir_all(backend_dir.join("idea-home/plugins/kast-headless"))
        .expect("kast-headless plugin");

    let bundled_kast = bundle.join("bin/kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &bundled_kast).expect("copy test kast binary");
    std::fs::write(backend_dir.join("kast-headless"), "#!/bin/sh\n").expect("launcher");
    std::fs::write(
        backend_dir.join("runtime-libs/classpath.txt"),
        "kast-test.jar\n",
    )
    .expect("classpath");
    std::fs::write(backend_dir.join("runtime-libs/kast-test.jar"), b"jar").expect("jar");
    std::fs::write(backend_dir.join("idea-home/lib/nio-fs.jar"), b"nio").expect("nio");
    std::fs::write(
        backend_dir.join("idea-home/modules/module-descriptors.dat"),
        b"modules",
    )
    .expect("module descriptors");
    std::fs::write(
        bundle.join("scripts/install-ubuntu-debian.sh"),
        "#!/usr/bin/env bash\n",
    )
    .expect("bootstrap script");
    set_executable_for_test(&bundled_kast);
    set_executable_for_test(&backend_dir.join("kast-headless"));
    set_executable_for_test(&bundle.join("scripts/install-ubuntu-debian.sh"));

    let normalized_version = version.trim_start_matches('v');
    std::fs::write(
        bundle.join("manifest.json"),
        serde_json::to_string_pretty(&serde_json::json!({
            "schemaVersion": 2,
            "kind": "KAST_INSTALL_BUNDLE",
            "profile": "ubuntu-debian-headless",
            "version": version,
            "platform": platform,
            "entrypoint": "scripts/install-ubuntu-debian.sh",
            "javaRequirement": "Java 21 or newer available on PATH, or KAST_JAVA_CMD set",
            "buildCommit": "test",
            "activation": {
                "cli": {"path": "bin/kast"},
                "backend": {
                    "kind": "headless",
                    "name": "headless",
                    "version": normalized_version,
                    "installDir": format!("lib/backends/headless-{version}"),
                    "launcher": "kast-headless",
                    "runtimeLibsDir": "runtime-libs",
                    "ideaHome": "idea-home",
                    "requiredPlugin": "idea-home/plugins/kast-headless"
                },
                "shim": {
                    "javaOpts": ["-Didea.force.use.core.classloader=true"],
                    "exportsInstallRoot": true,
                    "exportsConfigHome": true
                }
            },
            "artifacts": [
                {
                    "role": "cli",
                    "path": "bin/kast",
                    "sourceSha256": "test-cli-sha"
                },
                {
                    "role": "headless-backend",
                    "path": format!("lib/backends/headless-{version}"),
                    "sourceSha256": "test-backend-sha"
                }
            ]
        }))
        .expect("bundle manifest"),
    )
    .expect("write manifest");
    bundle
}

fn write_bundle_tarball(root: &Path, bundle: &Path) -> PathBuf {
    let tarball = root.join(format!(
        "{}.tar.gz",
        bundle
            .file_name()
            .and_then(|name| name.to_str())
            .expect("bundle name")
    ));
    let file = std::fs::File::create(&tarball).expect("tarball file");
    let encoder = flate2::write::GzEncoder::new(file, flate2::Compression::default());
    let mut archive = tar::Builder::new(encoder);
    archive
        .append_dir_all(bundle.file_name().expect("bundle name"), bundle)
        .expect("append bundle");
    archive.finish().expect("finish tar");
    let encoder = archive.into_inner().expect("finish encoder");
    encoder.finish().expect("finish gzip");
    tarball
}

fn write_malicious_bundle_tarball(root: &Path) -> PathBuf {
    let tarball = root.join("malicious.tar.gz");
    let file = std::fs::File::create(&tarball).expect("tarball file");
    let encoder = flate2::write::GzEncoder::new(file, flate2::Compression::default());
    let mut archive = tar::Builder::new(encoder);
    let mut header = tar::Header::new_gnu();
    header.set_entry_type(tar::EntryType::Symlink);
    header.set_path("bundle/link").expect("link path");
    header.set_link_name("/tmp/outside").expect("link target");
    header.set_size(0);
    header.set_mode(0o777);
    header.set_cksum();
    archive
        .append(&header, std::io::empty())
        .expect("append malicious member");
    archive.finish().expect("finish tar");
    let encoder = archive.into_inner().expect("finish encoder");
    encoder.finish().expect("finish gzip");
    tarball
}

#[cfg(unix)]
fn set_executable_for_test(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = std::fs::metadata(path).expect("metadata").permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(path, permissions).expect("mode");
}

#[cfg(not(unix))]
fn set_executable_for_test(_path: &Path) {}

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
    let help_stdout = String::from_utf8_lossy(&help.stdout);
    assert!(help_stdout.contains("Usage: kast"));
    assert!(
        !help_stdout.contains("agent"),
        "hidden agent command should not appear in top-level help: {help_stdout}"
    );
    assert!(
        !help_stdout.contains("rpc"),
        "raw rpc transport should not appear in top-level help: {help_stdout}"
    );

    let agent_help = kast(&home, &config_home)
        .args(["agent", "--help"])
        .output()
        .expect("agent help");
    assert!(agent_help.status.success());
    let agent_help_stdout = String::from_utf8_lossy(&agent_help.stdout);
    assert!(agent_help_stdout.contains("call"));
    assert!(agent_help_stdout.contains("raw-resolve"));

    let invalid_agent_call = kast(&home, &config_home)
        .args(["agent", "call", "symbol/resolve"])
        .output()
        .expect("agent validation failure");
    assert!(
        !invalid_agent_call.status.success(),
        "missing required params should fail validation before dispatch"
    );
    let invalid_agent_json: serde_json::Value =
        serde_json::from_slice(&invalid_agent_call.stdout).expect("agent validation json");
    assert_eq!(invalid_agent_json["ok"], false);
    assert_eq!(invalid_agent_json["method"], "symbol/resolve");
    assert_eq!(invalid_agent_json["error"]["code"], "AGENT_REQUEST_INVALID");

    let install_help = kast(&home, &config_home)
        .args(["install", "--help"])
        .output()
        .expect("install help");
    assert!(install_help.status.success());
    let install_help_stdout = String::from_utf8_lossy(&install_help.stdout);
    for command in [
        "activate-bundle",
        "plugin",
        "skill",
        "instructions",
        "copilot",
        "shell",
        "completion",
    ] {
        assert!(
            install_help_stdout.contains(command),
            "install help should list {command}: {install_help_stdout}"
        );
    }
    let activate_bundle_help = kast(&home, &config_home)
        .args(["install", "activate-bundle", "--help"])
        .output()
        .expect("install activate-bundle help");
    assert!(activate_bundle_help.status.success());
    let activate_bundle_stdout = String::from_utf8_lossy(&activate_bundle_help.stdout);
    assert!(
        activate_bundle_stdout.contains("--verify-only"),
        "install activate-bundle help should expose read-only verification: {activate_bundle_stdout}"
    );

    for command in ["plugin", "skill", "instructions", "copilot"] {
        let help = kast(&home, &config_home)
            .args(["install", command, "--help"])
            .output()
            .unwrap_or_else(|error| panic!("install {command} help: {error}"));
        assert!(
            help.status.success(),
            "install {command} help should succeed"
        );
        let stdout = String::from_utf8_lossy(&help.stdout);
        assert!(
            stdout.contains("-f, --force"),
            "install {command} help should expose -f/--force: {stdout}"
        );
    }
    let shell_help = kast(&home, &config_home)
        .args(["install", "shell", "--help"])
        .output()
        .expect("install shell help");
    assert!(shell_help.status.success());
    let shell_help_stdout = String::from_utf8_lossy(&shell_help.stdout);
    assert!(
        shell_help_stdout.contains("--shell"),
        "install shell help should expose --shell: {shell_help_stdout}"
    );

    let lsp_help = kast(&home, &config_home)
        .args(["lsp", "--help"])
        .output()
        .expect("lsp help");
    assert!(lsp_help.status.success());
    let lsp_help_stdout = String::from_utf8_lossy(&lsp_help.stdout);
    for visible in [
        "--stdio",
        "--workspace-root",
        "--backend",
        "--request-timeout-ms",
    ] {
        assert!(
            lsp_help_stdout.contains(visible),
            "lsp help should expose {visible}: {lsp_help_stdout}"
        );
    }

    let lsp_without_stdio = kast(&home, &config_home)
        .arg("lsp")
        .output()
        .expect("lsp without stdio");
    assert!(
        !lsp_without_stdio.status.success(),
        "lsp without --stdio should fail closed"
    );
    assert!(
        String::from_utf8_lossy(&lsp_without_stdio.stderr).contains("kast lsp --stdio"),
        "lsp usage error should name the supported command: stderr={}",
        String::from_utf8_lossy(&lsp_without_stdio.stderr)
    );
    assert!(
        shell_help_stdout.contains("--profile"),
        "install shell help should expose --profile: {shell_help_stdout}"
    );
    let demo_help = kast(&home, &config_home)
        .args(["demo", "--help"])
        .output()
        .expect("demo help");
    assert!(demo_help.status.success());
    let demo_help_stdout = String::from_utf8_lossy(&demo_help.stdout);
    assert!(demo_help_stdout.contains("source-index demo"));
    assert!(demo_help_stdout.contains("compare"));

    let repair = kast(&home, &config_home)
        .args(["doctor", "--repair"])
        .output()
        .expect("doctor repair");
    assert!(
        repair.status.success(),
        "doctor --repair should converge the install: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );
    assert!(install_manifest_path(&home).is_file());

    let skill_dir = temp.path().join("skills");
    let skill = kast(&home, &config_home)
        .args([
            "install",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "--force",
        ])
        .output()
        .expect("install skill");
    assert!(skill.status.success());
    assert!(skill_dir.join("kast/SKILL.md").is_file());
    assert!(skill_dir.join("kast/references/commands.json").is_file());
    assert!(skill_dir.join("kast/references/quickstart.md").is_file());
    assert!(
        skill_dir
            .join("kast/references/requests/symbol/query/request.schema.json")
            .is_file()
    );
    let instructions_dir = temp.path().join("instructions");
    let instructions = kast(&home, &config_home)
        .args([
            "install",
            "instructions",
            "--target-dir",
            instructions_dir.to_str().expect("instructions path"),
            "--force",
        ])
        .output()
        .expect("install instructions");
    assert!(instructions.status.success());
    assert!(instructions_dir.join("kast/README.md").is_file());
    assert!(instructions_dir.join("kast/cli.md").is_file());
    assert!(instructions_dir.join("kast/rpc.md").is_file());
    assert!(instructions_dir.join("kast/lsp.md").is_file());

    let github_dir = temp.path().join(".github");
    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");
    assert!(copilot.status.success());
    assert!(github_dir.join("lsp.json").is_file());
    assert!(github_dir.join("agents/kast-reader.agent.md").is_file());
    assert!(github_dir.join("agents/kast-writer.agent.md").is_file());
    assert!(github_dir.join(".kast-copilot-version").is_file());

    let status = kast(&home, &config_home)
        .args([
            "--output",
            "json",
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
fn paths_report_distinguishes_global_defaults_from_workspace_cache_env() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let cache_home = temp.path().join("cache-home");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let global_paths = kast(&home, &config_home)
        .env("KAST_CACHE_HOME", &cache_home)
        .args(["--output", "json", "paths"])
        .output()
        .expect("global paths");
    assert!(
        global_paths.status.success(),
        "global paths should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&global_paths.stdout),
        String::from_utf8_lossy(&global_paths.stderr)
    );
    let global: serde_json::Value =
        serde_json::from_slice(&global_paths.stdout).expect("global paths json");
    assert_eq!(
        path_report_entry(&global, "paths.cacheDir")["source"],
        "env"
    );
    assert_eq!(
        path_report_entry(&global, "paths.cacheDir")["value"],
        cache_home.display().to_string()
    );
    assert_eq!(
        path_report_entry(&global, "paths.logsDir")["source"],
        "default"
    );
    assert!(
        path_report_entry(&global, "paths.logsDir")
            .get("derivedFrom")
            .is_none()
    );
    assert_eq!(
        path_report_entry(&global, "paths.descriptorDir")["source"],
        "default"
    );
    assert_eq!(
        path_report_entry(&global, "paths.descriptorDir")["derivedFrom"],
        "paths.runtimeDir"
    );
    assert_eq!(
        path_report_entry(&global, "paths.socketDir")["source"],
        "default"
    );
    assert_eq!(
        path_report_entry(&global, "paths.socketDir")["derivedFrom"],
        "paths.runtimeDir"
    );

    let workspace_paths = kast(&home, &config_home)
        .env("KAST_CACHE_HOME", &cache_home)
        .args([
            "--output",
            "json",
            "paths",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("workspace paths");
    assert!(
        workspace_paths.status.success(),
        "workspace paths should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&workspace_paths.stdout),
        String::from_utf8_lossy(&workspace_paths.stderr)
    );
    let workspace: serde_json::Value =
        serde_json::from_slice(&workspace_paths.stdout).expect("workspace paths json");
    for key in [
        "paths.cacheDir",
        "paths.logsDir",
        "paths.descriptorDir",
        "paths.socketDir",
    ] {
        assert_eq!(path_report_entry(&workspace, key)["source"], "env");
    }
    for key in ["paths.logsDir", "paths.descriptorDir", "paths.socketDir"] {
        assert_eq!(
            path_report_entry(&workspace, key)["derivedFrom"],
            "paths.cacheDir"
        );
    }
}

#[test]
fn top_level_help_exposes_release_commands() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .arg("--help")
        .output()
        .expect("help");
    assert!(help.status.success());
    let stdout = String::from_utf8_lossy(&help.stdout);
    for command in [
        "package", "up", "status", "stop", "restart", "paths", "install", "doctor",
    ] {
        assert!(
            stdout
                .lines()
                .any(|line| line.trim_start().starts_with(command)),
            "top-level help should show {command}: {stdout}"
        );
    }
    let up_help = kast(&home, &config_home)
        .args(["up", "--help"])
        .output()
        .expect("up help");
    assert!(up_help.status.success());
    let up_help_stdout = String::from_utf8_lossy(&up_help.stdout);
    for visible in ["--workspace-root", "--backend"] {
        assert!(
            up_help_stdout.contains(visible),
            "up help should retain primary flag {visible}: {up_help_stdout}"
        );
    }

    let install_help = kast(&home, &config_home)
        .args(["install", "--help"])
        .output()
        .expect("install help");
    assert!(install_help.status.success());
    let install_help_stdout = String::from_utf8_lossy(&install_help.stdout);
    assert!(
        install_help_stdout.contains("activate-bundle"),
        "install help should expose bundle activation: {install_help_stdout}"
    );

    let package_help = kast(&home, &config_home)
        .args(["package", "--help"])
        .output()
        .expect("package help");
    assert!(package_help.status.success());
    let package_help_stdout = String::from_utf8_lossy(&package_help.stdout);
    assert!(
        package_help_stdout.contains("ubuntu-debian-bundle"),
        "package help should expose Ubuntu/Debian bundle packaging: {package_help_stdout}"
    );

    let doctor_help = kast(&home, &config_home)
        .args(["doctor", "--help"])
        .output()
        .expect("doctor help");
    assert!(doctor_help.status.success());
    let doctor_stdout = String::from_utf8_lossy(&doctor_help.stdout);
    assert!(
        doctor_stdout.contains("--repair"),
        "doctor help should expose the single repair surface: {doctor_stdout}"
    );
}

#[test]
fn install_completion_command_renders_shell_completion_scripts() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let bash = kast(&home, &config_home)
        .args(["install", "completion", "bash"])
        .output()
        .expect("bash completion");
    assert!(
        bash.status.success(),
        "bash completion should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&bash.stdout),
        String::from_utf8_lossy(&bash.stderr)
    );
    let bash_stdout = String::from_utf8_lossy(&bash.stdout);
    assert!(
        bash_stdout.contains("complete"),
        "bash completion should register a completion function: {bash_stdout}"
    );
    assert!(
        bash_stdout.contains("kast"),
        "bash completion should target the kast command: {bash_stdout}"
    );

    let zsh = kast(&home, &config_home)
        .args(["install", "completion", "zsh", "--command-name", "kast-dev"])
        .output()
        .expect("zsh completion");
    assert!(
        zsh.status.success(),
        "zsh completion should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&zsh.stdout),
        String::from_utf8_lossy(&zsh.stderr)
    );
    let zsh_stdout = String::from_utf8_lossy(&zsh.stdout);
    assert!(
        zsh_stdout.contains("#compdef kast-dev"),
        "zsh completion should use the requested command name: {zsh_stdout}"
    );
}

#[test]
fn install_shell_writes_path_and_completion_profile_integration() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let profile = temp.path().join(".zshrc");
    let empty_path = temp.path().join("empty-path");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config");
    std::fs::create_dir_all(&empty_path).expect("empty path");
    std::fs::write(
        config_home.join("config.toml"),
        "[paths]\nbinDir = \"/ignored\"\n",
    )
    .expect("config");
    let expected_bin_dir = default_bin_dir(&home);

    let install = kast(&home, &config_home)
        .env("PATH", &empty_path)
        .args([
            "--output",
            "json",
            "install",
            "shell",
            "--shell",
            "zsh",
            "--profile",
            profile.to_str().expect("profile path"),
            "--command-name",
            "kast-dev",
        ])
        .output()
        .expect("install shell");
    assert!(
        install.status.success(),
        "install shell should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install shell json");
    assert_eq!(stdout["shell"], "zsh");
    assert_eq!(stdout["commandName"], "kast-dev");
    assert_eq!(stdout["binDir"], expected_bin_dir.display().to_string());
    assert_eq!(stdout["profileUpdated"], true);

    let source_file = PathBuf::from(stdout["sourceFile"].as_str().expect("source file"));
    let source = std::fs::read_to_string(&source_file).expect("source file content");
    assert!(
        source.contains(&format!(
            "export KAST_CONFIG_HOME={}",
            shell_single_quote(config_home.to_str().expect("config path"))
        )),
        "source file should export the active config home: {source}"
    );
    assert!(
        source.contains(&format!(
            "_kast_bin_dir={}",
            shell_single_quote(&expected_bin_dir.display().to_string())
        )),
        "source file should store the configured bin directory: {source}"
    );
    assert!(
        source.contains("export PATH=\"${_kast_bin_dir}:${PATH}\""),
        "source file should prepend the configured bin directory: {source}"
    );
    assert!(
        source.contains("kast-dev install completion zsh --command-name kast-dev"),
        "source file should wire completions for kast-dev: {source}"
    );

    let profile_content = std::fs::read_to_string(&profile).expect("profile content");
    assert!(
        profile_content.contains("# >>> kast shell integration >>>"),
        "profile should contain a managed block: {profile_content}"
    );
    assert!(
        profile_content.contains(&format!(
            "source {}",
            shell_single_quote(source_file.to_str().expect("source file path"))
        )),
        "profile should source the managed integration file: {profile_content}"
    );
}

#[test]
fn install_shell_prefers_running_cli_directory_over_stale_config_bin_dir() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let stale_bin = home.join(".kast/bin");
    let profile = temp.path().join(".zshrc");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
binDir = "{}"
"#,
            stale_bin.display()
        ),
    )
    .expect("config");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "shell",
            "--shell",
            "zsh",
            "--profile",
            profile.to_str().expect("profile path"),
        ])
        .output()
        .expect("install shell");

    assert!(
        install.status.success(),
        "install shell should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install shell json");
    let running_bin = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    assert_eq!(stdout["commandName"], "kast");
    assert_eq!(stdout["binDir"], running_bin.display().to_string());
    let source_file = PathBuf::from(stdout["sourceFile"].as_str().expect("source file"));
    let source = std::fs::read_to_string(&source_file).expect("source file content");
    assert!(
        !source.contains(&stale_bin.display().to_string()),
        "source file should not point at stale config binDir: {source}"
    );
    assert!(
        source.contains(&running_bin.display().to_string()),
        "source file should point at the running kast binary directory: {source}"
    );
}

fn shell_single_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

#[test]
fn help_topic_dumps_selected_command_help() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let help = kast(&home, &config_home)
        .args(["help", "install", "plugin"])
        .output()
        .expect("help topic");

    assert!(
        help.status.success(),
        "help topic should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&help.stdout),
        String::from_utf8_lossy(&help.stderr)
    );
    let stdout = String::from_utf8_lossy(&help.stdout);
    assert!(
        stdout.contains("Homebrew-managed IDEA plugin"),
        "selected help should include the command description: {stdout}"
    );
    assert!(
        stdout.contains("--jetbrains-config-root"),
        "selected help should include the command flags: {stdout}"
    );
    assert!(
        !stdout.contains("Help topic:"),
        "topic help should not use the placeholder renderer: {stdout}"
    );
}

#[test]
fn lifecycle_commands_render_human_text_by_default_and_json_when_selected() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let human = kast(&home, &config_home)
        .args([
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status human");

    assert!(
        human.status.success(),
        "human status should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&human.stdout),
        String::from_utf8_lossy(&human.stderr)
    );
    let stdout = String::from_utf8_lossy(&human.stdout);
    assert!(
        stdout.starts_with("Kast status\n===========\n"),
        "status should default to a rendered readable summary: {stdout}"
    );
    assert!(
        stdout.contains("No runtime candidates were found."),
        "status should include an actionable empty-state message: {stdout}"
    );
    assert!(
        stdout.contains("Next steps\n----------"),
        "status should render Markdown section headings: {stdout}"
    );
    assert!(
        !stdout.contains("# Kast status") && !stdout.contains("`kast up`"),
        "status should not dump raw Markdown control tokens: {stdout}"
    );
    assert!(
        serde_json::from_slice::<serde_json::Value>(&human.stdout).is_err(),
        "default status output should not be JSON"
    );

    let json = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("status json");

    assert!(
        json.status.success(),
        "json status should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&json.stdout),
        String::from_utf8_lossy(&json.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&json.stdout).expect("status json");
    assert_eq!(
        stdout["candidates"].as_array().expect("candidates").len(),
        0
    );
}

#[test]
fn stop_removes_every_matching_stale_headless_descriptor() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "headless",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 0,
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "headless",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 999999999,
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 0,
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            temp.path().join("one.sock").display(),
            workspace.display(),
            temp.path().join("two.sock").display(),
            workspace.display(),
            temp.path().join("idea.sock").display(),
        ),
    )
    .expect("descriptors");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "headless",
        ])
        .output()
        .expect("stop");

    assert!(
        stop.status.success(),
        "stop should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&stop.stdout),
        String::from_utf8_lossy(&stop.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&stop.stdout).expect("stop json");
    assert_eq!(stdout["backendName"], "headless");
    assert_eq!(stdout["stopped"], true);
    assert_eq!(stdout["stoppedCount"], 2);
    assert_eq!(
        stdout["candidates"].as_array().expect("candidates").len(),
        2
    );

    let remaining: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(descriptor_dir.join("daemons.json"))
            .expect("remaining descriptors"),
    )
    .expect("remaining descriptor json");
    let remaining = remaining.as_array().expect("remaining descriptor array");
    assert_eq!(remaining.len(), 1);
    assert_eq!(remaining[0]["backendName"], "idea");
}

#[test]
fn stop_requests_reachable_idea_backend_shutdown() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("config");
    let descriptor_file = descriptor_dir.join("daemons.json");
    std::fs::write(
        &descriptor_file,
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": {},
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
        ),
    )
    .expect("descriptors");

    let listener = UnixListener::bind(&socket_path).expect("bind fake idea socket");
    let server_workspace = workspace.clone();
    let server_descriptor_file = descriptor_file.clone();
    let handle = thread::spawn(move || {
        let mut methods = Vec::new();
        for _ in 0..3 {
            let (mut stream, _) = listener.accept().expect("accept fake idea client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read fake idea request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "runtime/shutdown" => {
                    let result = serde_json::json!({
                        "accepted": true,
                        "action": "SHUTDOWN",
                        "backendName": "idea",
                        "backendVersion": "test",
                        "workspaceRoot": server_workspace.display().to_string(),
                        "schemaVersion": 3
                    });
                    writeln!(
                        stream,
                        "{}",
                        serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
                    )
                    .expect("write shutdown response");
                    std::fs::remove_file(&server_descriptor_file).expect("remove descriptor");
                    break;
                }
                other => panic!("unexpected fake idea method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write fake idea response");
        }
        methods
    });

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("stop");

    assert!(
        stop.status.success(),
        "stop should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&stop.stdout),
        String::from_utf8_lossy(&stop.stderr)
    );
    let methods = handle.join().expect("fake idea server");
    assert_eq!(
        methods,
        vec!["runtime/status", "capabilities", "runtime/shutdown"]
    );
    let stdout: serde_json::Value = serde_json::from_slice(&stop.stdout).expect("stop json");
    assert_eq!(stdout["backendName"], "idea");
    assert_eq!(stdout["stopped"], true);
    assert_eq!(stdout["stoppedCount"], 1);
    assert_eq!(stdout["candidates"][0]["lifecycleAccepted"], true);
    assert_eq!(
        stdout["candidates"][0]["lifecycleMethod"],
        "runtime/shutdown"
    );
    assert_eq!(stdout["candidates"][0]["lifecycleAction"], "SHUTDOWN");
    assert!(
        !descriptor_file.exists(),
        "IDEA lifecycle shutdown should remove the descriptor"
    );
}

#[test]
fn restart_requests_reachable_idea_backend_restart() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let descriptor_dir = default_descriptor_dir(&home);
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        format!(
            r#"[
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test",
    "transport": "uds",
    "socketPath": "{}",
    "pid": {},
    "schemaVersion": 3
  }},
  {{
    "workspaceRoot": "{}",
    "backendName": "idea",
    "backendVersion": "test-stale",
    "transport": "uds",
    "socketPath": "{}",
    "pid": 1,
    "schemaVersion": 3
  }}
]"#,
            workspace.display(),
            socket_path.display(),
            std::process::id(),
            workspace.display(),
            temp.path().join("stale-idea.sock").display(),
        ),
    )
    .expect("descriptors");

    let listener = UnixListener::bind(&socket_path).expect("bind fake idea socket");
    let server_workspace = workspace.clone();
    let handle = thread::spawn(move || {
        let mut methods = Vec::new();
        for _ in 0..5 {
            let (mut stream, _) = listener.accept().expect("accept fake idea client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read fake idea request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "runtime/restart" => serde_json::json!({
                    "accepted": true,
                    "action": "RESTART",
                    "backendName": "idea",
                    "backendVersion": "test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                other => panic!("unexpected fake idea method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write fake idea response");
        }
        methods
    });

    let restart = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "restart",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("restart");

    assert!(
        restart.status.success(),
        "restart should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&restart.stdout),
        String::from_utf8_lossy(&restart.stderr)
    );
    let methods = handle.join().expect("fake idea server");
    assert_eq!(
        methods,
        vec![
            "runtime/status",
            "capabilities",
            "runtime/restart",
            "runtime/status",
            "capabilities",
        ]
    );
    let stdout: serde_json::Value = serde_json::from_slice(&restart.stdout).expect("restart json");
    assert_eq!(stdout["backendName"], "idea");
    assert_eq!(stdout["stop"]["stopped"], true);
    assert_eq!(stdout["stop"]["stoppedCount"], 2);
    assert_eq!(stdout["stop"]["candidates"][0]["lifecycleAccepted"], true);
    assert_eq!(
        stdout["stop"]["candidates"][0]["lifecycleMethod"],
        "runtime/restart"
    );
    assert_eq!(
        stdout["stop"]["candidates"][0]["lifecycleAction"],
        "RESTART"
    );
    assert_eq!(stdout["ensure"]["started"], false);
    assert_eq!(
        stdout["ensure"]["selected"]["descriptor"]["backendName"],
        "idea"
    );
    let remaining: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(descriptor_dir.join("daemons.json"))
            .expect("remaining descriptors"),
    )
    .expect("remaining descriptor json");
    let remaining = remaining.as_array().expect("remaining descriptor array");
    assert_eq!(remaining.len(), 1);
    assert_eq!(remaining[0]["backendVersion"], "test");
}

#[test]
fn lifecycle_commands_walk_up_to_workspace_marker_when_root_is_omitted() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let nested = workspace.join("app/src/main/kotlin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&nested).expect("nested");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "pluginManagement {}\n",
    )
    .expect("settings marker");

    let status = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(&nested)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args(["--output", "json", "status"])
        .output()
        .expect("status");

    assert!(
        status.status.success(),
        "status should resolve workspace marker from cwd: stdout={}, stderr={}",
        String::from_utf8_lossy(&status.stdout),
        String::from_utf8_lossy(&status.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&status.stdout).expect("status json");
    let expected_workspace = std::fs::canonicalize(&workspace).expect("canonical workspace");
    assert_eq!(
        stdout["workspaceRoot"].as_str().expect("workspace root"),
        expected_workspace.to_str().expect("workspace path")
    );
}

#[test]
fn package_ubuntu_debian_bundle_writes_manifest_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo_root = temp.path().join("repo");
    let output = temp
        .path()
        .join("dist/kast-ubuntu-debian-headless-x86_64-v9.8.7.tar.gz");
    std::fs::create_dir_all(repo_root.join("scripts")).expect("repo scripts");
    std::fs::write(
        repo_root.join("scripts/install-ubuntu-debian.sh"),
        "#!/usr/bin/env bash\n",
    )
    .expect("bootstrap script");
    set_executable_for_test(&repo_root.join("scripts/install-ubuntu-debian.sh"));

    let cli_archive = write_cli_archive(temp.path());
    let backend_archive = write_backend_archive(temp.path(), "headless", "v9.8.7");

    let package = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "package",
            "ubuntu-debian-bundle",
            "--repo-root",
            repo_root.to_str().expect("repo root"),
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--version",
            "v9.8.7",
            "--bundle-output",
            output.to_str().expect("output"),
        ])
        .output()
        .expect("package ubuntu debian bundle");

    assert!(
        package.status.success(),
        "package should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&package.stdout),
        String::from_utf8_lossy(&package.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&package.stdout).expect("package json");
    assert_eq!(stdout["version"], "v9.8.7");
    assert_eq!(stdout["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(stdout["manifestSchemaVersion"], 2);
    assert_eq!(stdout["output"], output.display().to_string());
    assert_eq!(
        stdout["sha256Sidecar"],
        format!("{}.sha256", output.display())
    );
    assert!(output.is_file(), "bundle tarball exists");
    assert!(PathBuf::from(format!("{}.sha256", output.display())).is_file());

    let extract_dir = temp.path().join("extract");
    std::fs::create_dir_all(&extract_dir).expect("extract dir");
    let file = std::fs::File::open(&output).expect("bundle tarball");
    let decoder = flate2::read::GzDecoder::new(file);
    let mut archive = tar::Archive::new(decoder);
    archive.unpack(&extract_dir).expect("unpack bundle");
    let bundle_root = extract_dir.join("kast-ubuntu-debian-headless_x86_64-v9.8.7");
    assert!(
        !bundle_root.exists(),
        "bundle root must use the canonical hyphenated/underscored platform id"
    );
    let bundle_root = extract_dir.join("kast-ubuntu-debian-headless-x86_64-v9.8.7");
    assert!(bundle_root.join("bin/kast").is_file());
    assert!(
        bundle_root
            .join("lib/backends/headless-v9.8.7/kast-headless")
            .is_file()
    );
    assert!(
        bundle_root
            .join("scripts/install-ubuntu-debian.sh")
            .is_file()
    );

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(bundle_root.join("manifest.json")).expect("manifest"),
    )
    .expect("manifest json");
    assert_eq!(manifest["schemaVersion"], 2);
    assert_eq!(manifest["kind"], "KAST_INSTALL_BUNDLE");
    assert_eq!(manifest["profile"], "ubuntu-debian-headless");
    assert_eq!(manifest["version"], "v9.8.7");
    assert_eq!(manifest["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(manifest["entrypoint"], "scripts/install-ubuntu-debian.sh");
    assert_eq!(manifest["activation"]["cli"]["path"], "bin/kast");
    assert_eq!(manifest["activation"]["backend"]["kind"], "headless");
    assert_eq!(manifest["activation"]["backend"]["name"], "headless");
    assert_eq!(manifest["activation"]["backend"]["version"], "9.8.7");
    assert_eq!(
        manifest["activation"]["backend"]["installDir"],
        "lib/backends/headless-v9.8.7"
    );
    assert_eq!(
        manifest["activation"]["backend"]["requiredPlugin"],
        "idea-home/plugins/kast-headless"
    );
    assert_eq!(
        manifest["activation"]["shim"]["javaOpts"][0],
        "-Didea.force.use.core.classloader=true"
    );
    assert_eq!(manifest["artifacts"][0]["role"], "cli");
    assert_eq!(manifest["artifacts"][1]["role"], "headless-backend");
}

#[test]
fn activate_bundle_installs_from_v2_manifest_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bin_dir = temp.path().join("bin");
    std::fs::create_dir_all(&home).expect("home");
    let bundle = write_install_bundle_source(temp.path(), "v0.7.11-ci");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("activate bundle");

    assert!(
        install.status.success(),
        "activate bundle should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("activate bundle json");
    assert_eq!(stdout["version"], "v0.7.11-ci");
    assert_eq!(stdout["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(stdout["profile"], "ubuntu-debian-headless");
    assert_eq!(stdout["skipped"], false);
    assert_eq!(stdout["verifyOnly"], false);

    let installed_home = install_root.join("versions/v0.7.11-ci");
    let manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(install_root.join("install.json")).unwrap())
            .expect("install manifest json");
    assert_eq!(manifest["tool"], "kast");
    assert_eq!(manifest["activeVersion"], "v0.7.11-ci");
    assert_eq!(manifest["version"], "0.7.11-ci");
    assert_eq!(manifest["backendVersion"], "0.7.11-ci");
    assert_eq!(
        manifest["entrypoints"]["activeBinary"],
        installed_home.join("bin/kast").display().to_string()
    );
    assert_eq!(
        manifest["backends"][0]["runtimeLibsDir"],
        installed_home
            .join("lib/backends/headless/current/runtime-libs")
            .display()
            .to_string()
    );
    assert!(install_root.join("current").exists());
    assert!(bin_dir.join("kast").is_file());
    let shim = std::fs::read_to_string(bin_dir.join("kast")).expect("shim");
    assert!(shim.contains("KAST_INSTALL_ROOT"));
    assert!(shim.contains("KAST_CONFIG_HOME"));
    assert!(shim.contains("-Didea.force.use.core.classloader=true"));
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("defaultBackend = \"headless\""));
    assert!(!config.contains("runtimeLibsDir"));

    let reinstall = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("reactivate bundle");
    assert!(
        reinstall.status.success(),
        "reactivate bundle should be idempotent: stdout={}, stderr={}",
        String::from_utf8_lossy(&reinstall.stdout),
        String::from_utf8_lossy(&reinstall.stderr)
    );
    let reinstall_stdout: serde_json::Value =
        serde_json::from_slice(&reinstall.stdout).expect("reinstall json");
    assert_eq!(reinstall_stdout["skipped"], true);
}

#[test]
fn activate_bundle_installs_from_tarball_source() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bin_dir = temp.path().join("bin");
    std::fs::create_dir_all(&home).expect("home");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let tarball = write_bundle_tarball(temp.path(), &bundle);

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            tarball.to_str().expect("tarball path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("activate bundle tarball");

    assert!(
        install.status.success(),
        "tarball activation should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    assert!(install_root.join("install.json").is_file());
    assert!(bin_dir.join("kast").is_file());
}

#[test]
fn activate_bundle_rejects_unsupported_manifest_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = bundle.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).unwrap()).unwrap();
    manifest["schemaVersion"] = serde_json::json!(1);
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate unsupported bundle");

    assert!(!install.status.success(), "unsupported bundle should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_MANIFEST_UNSUPPORTED"), "{stderr}");
    assert!(!install_root.join("install.json").exists());
}

#[test]
fn activate_bundle_rejects_unsafe_manifest_version_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let victim = temp.path().join("victim");
    std::fs::create_dir_all(&victim).expect("victim dir");
    std::fs::write(victim.join("marker"), b"do not replace").expect("victim marker");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = bundle.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).unwrap()).unwrap();
    manifest["version"] = serde_json::json!("../../victim");
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate unsafe-version bundle");

    assert!(!install.status.success(), "unsafe version should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_MANIFEST_INVALID"), "{stderr}");
    assert!(
        victim.join("marker").is_file(),
        "unsafe version must not delete or replace paths outside the install root"
    );
    assert!(!install_root.join("install.json").exists());
}

#[test]
fn activate_bundle_rejects_unsafe_tar_member_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let tarball = write_malicious_bundle_tarball(temp.path());

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            tarball.to_str().expect("tarball path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate malicious tarball");

    assert!(!install.status.success(), "unsafe tarball should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_ARCHIVE_INVALID"), "{stderr}");
    assert!(!install_root.join("install.json").exists());
    assert!(!temp.path().join("outside").exists());
}

#[test]
fn activate_bundle_verify_only_is_read_only_when_missing_install() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "activate-bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--verify-only",
        ])
        .output()
        .expect("verify missing activation");

    assert!(
        !verify.status.success(),
        "verify-only should fail without install"
    );
    assert!(!install_root.join("install.json").exists());
    assert!(!install_root.join("versions").exists());
}

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
fn rpc_uses_configured_default_backend_when_auto_starting() {
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

    let rpc = kast(&home, &config_home)
        .args([
            "rpc",
            r#"{"jsonrpc":"2.0","method":"runtime/status","id":1}"#,
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("rpc");

    assert!(
        !rpc.status.success(),
        "rpc should fail without an installed headless backend"
    );
    let stderr = String::from_utf8_lossy(&rpc.stderr);
    assert!(
        stderr.contains("Linux headless tarball"),
        "stderr should point to the supported headless distribution: {stderr}"
    );
}

#[test]
fn rpc_backend_flag_overrides_configured_default_backend() {
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

    let rpc = kast(&home, &config_home)
        .args([
            "rpc",
            r#"{"jsonrpc":"2.0","method":"runtime/status","id":1}"#,
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
        ])
        .output()
        .expect("rpc");

    assert!(
        !rpc.status.success(),
        "rpc should fail without an installed headless backend"
    );
    let stderr = String::from_utf8_lossy(&rpc.stderr);
    assert!(
        stderr.contains("Linux headless tarball"),
        "stderr should point to the supported headless distribution: {stderr}"
    );
}

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
        .args(["--output", "json", "install", "plugin", "--dry-run"])
        .output()
        .expect("install idea plugin");

    assert!(
        !install.status.success(),
        "default install should require JetBrains profiles instead of downloading a zip: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("JETBRAINS_CONFIG_NOT_FOUND"), "{stderr}");
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
            "install",
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
    #[cfg(unix)]
    assert_eq!(
        std::fs::read_link(jetbrains_root.join("IntelliJIdea2026.1/plugins/kast"))
            .expect("plugin symlink"),
        Path::new("/opt/homebrew/Caskroom/kast-plugin/9.8.7/backend-idea")
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
            "install",
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
        Path::new("/opt/homebrew/Caskroom/kast-plugin/9.8.7/backend-idea")
    );
}

#[test]
fn doctor_repair_writes_manifest_and_removes_install_owned_config() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = home.join(".local/share/kast");
    let stale_bin = temp.path().join("stale-bin");
    let stale_runtime_libs = temp.path().join("runtime-libs");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
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
shellRcPatches = []
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

    let read_only = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("doctor");
    assert!(
        !read_only.status.success(),
        "plain doctor should remain read-only and report missing manifest"
    );
    assert!(!install_manifest_path(&home).exists());
    assert!(
        std::fs::read_to_string(config_home.join("config.toml"))
            .expect("config after plain doctor")
            .contains("[install]")
    );

    let repair = kast(&home, &config_home)
        .args(["--output", "json", "doctor", "--repair"])
        .output()
        .expect("doctor repair");

    assert!(
        repair.status.success(),
        "doctor --repair should repair stale state: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert_eq!(stdout["repair"]["applied"], true);
    assert!(
        stdout["repair"]["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "remove-install-owned-config"),
        "doctor --repair should remove install-owned TOML keys: {stdout}"
    );
    assert_eq!(stdout["install"]["tool"], "kast");
    assert!(install_manifest_path(&home).is_file());
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after repair");
    assert!(!config_after.contains("[paths]"));
    assert!(!config_after.contains("[cli]"));
    assert!(!config_after.contains("[install]"));
    assert!(!config_after.contains("binaryPath"));
    assert!(!config_after.contains("runtimeLibsDir"));
    assert!(!config_after.contains("ideaHome"));
}

#[test]
fn doctor_repair_recovers_malformed_global_config_with_backup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(config_home.join("config.toml"), "[runtime\n").expect("malformed config");

    let read_only = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("read-only doctor");

    assert!(
        !read_only.status.success(),
        "read-only doctor should report malformed config without changing files"
    );
    assert_eq!(
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after read-only"),
        "[runtime\n"
    );

    let apply = kast(&home, &config_home)
        .args(["--output", "json", "doctor", "--repair"])
        .output()
        .expect("doctor repair");

    assert!(
        apply.status.success(),
        "doctor --repair should recover malformed config: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr)
    );
    let apply_stdout: serde_json::Value =
        serde_json::from_slice(&apply.stdout).expect("apply json");
    assert_eq!(apply_stdout["repair"]["applied"], true);
    assert!(
        apply_stdout["repair"]["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "recover-invalid-config"),
        "apply should report config recovery: {apply_stdout}"
    );
    let backups = apply_stdout["repair"]["backups"]
        .as_array()
        .expect("backups");
    assert!(
        !backups.is_empty(),
        "apply should preserve the malformed config"
    );
    let backup =
        std::fs::read_to_string(backups[0].as_str().expect("backup path")).expect("backup content");
    assert_eq!(backup, "[runtime\n");
    let recovered =
        std::fs::read_to_string(config_home.join("config.toml")).expect("recovered config");
    assert!(!recovered.contains("[paths]"), "{recovered}");
    assert!(!recovered.contains("installRoot = "), "{recovered}");
    assert!(!recovered.contains("binDir = "), "{recovered}");
    assert!(!recovered.contains("binaryPath = "), "{recovered}");
    recovered
        .parse::<toml::Table>()
        .expect("recovered config should be valid TOML");
    assert!(!recovered.contains("[runtime\n"), "{recovered}");
    assert!(install_manifest_path(&home).is_file());
}

#[test]
fn install_resource_gateways_support_force_and_current_versions() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let skill_dir = temp.path().join("skills");
    let instructions_dir = temp.path().join("instructions");
    let github_dir = temp.path().join(".github");
    let stale_skill = skill_dir.join("kast");
    let stale_instructions = instructions_dir.join("kast");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&stale_skill).expect("stale skill");
    std::fs::create_dir_all(&stale_instructions).expect("stale instructions");
    std::fs::write(stale_skill.join(".kast-version"), b"old\n").expect("stale marker");
    std::fs::write(stale_instructions.join(".kast-version"), b"old\n")
        .expect("stale instructions marker");

    let skill = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "-f",
        ])
        .output()
        .expect("install skill");
    assert!(
        skill.status.success(),
        "skill install should accept -f: stdout={}, stderr={}",
        String::from_utf8_lossy(&skill.stdout),
        String::from_utf8_lossy(&skill.stderr)
    );
    let skill_stdout: serde_json::Value =
        serde_json::from_slice(&skill.stdout).expect("skill install json");
    assert!(stale_skill.join("SKILL.md").is_file());

    let forced_skill = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "skill",
            "--target-dir",
            skill_dir.to_str().expect("skill path"),
            "-f",
        ])
        .output()
        .expect("force reinstall skill");
    assert!(
        forced_skill.status.success(),
        "skill force reinstall should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&forced_skill.stdout),
        String::from_utf8_lossy(&forced_skill.stderr)
    );
    let forced_skill_stdout: serde_json::Value =
        serde_json::from_slice(&forced_skill.stdout).expect("forced skill json");
    assert_eq!(forced_skill_stdout["skipped"], false);

    let skill_marker =
        std::fs::read_to_string(stale_skill.join(".kast-version")).expect("skill marker");
    assert_eq!(skill_marker.trim(), skill_stdout["version"]);

    let instructions = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "instructions",
            "--target-dir",
            instructions_dir.to_str().expect("instructions path"),
            "-f",
        ])
        .output()
        .expect("install instructions");
    assert!(
        instructions.status.success(),
        "instructions install should accept -f: stdout={}, stderr={}",
        String::from_utf8_lossy(&instructions.stdout),
        String::from_utf8_lossy(&instructions.stderr)
    );
    let instructions_stdout: serde_json::Value =
        serde_json::from_slice(&instructions.stdout).expect("instructions install json");
    assert_eq!(
        instructions_stdout["installedAt"],
        stale_instructions.display().to_string()
    );
    assert!(stale_instructions.join("README.md").is_file());
    assert!(stale_instructions.join("cli.md").is_file());
    assert!(stale_instructions.join("rpc.md").is_file());
    assert!(stale_instructions.join("lsp.md").is_file());
    let instructions_marker = std::fs::read_to_string(stale_instructions.join(".kast-version"))
        .expect("instructions marker");
    assert_eq!(instructions_marker.trim(), instructions_stdout["version"]);

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--force",
        ])
        .output()
        .expect("install copilot");
    assert!(
        copilot.status.success(),
        "copilot install should accept --force: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr)
    );
    let copilot_stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(
        copilot_stdout["installedAt"],
        github_dir.display().to_string()
    );
    assert!(github_dir.join("lsp.json").is_file());
    assert!(
        github_dir
            .join("instructions/kast-kotlin.instructions.md")
            .is_file()
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-agents.mjs")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-trace.mjs")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/commands.json")
            .is_file()
    );
    assert!(github_dir.join("agents/kast-reader.agent.md").is_file());
    assert!(github_dir.join("agents/kast-writer.agent.md").is_file());

    let copilot_marker =
        std::fs::read_to_string(github_dir.join(".kast-copilot-version")).expect("copilot marker");
    assert_eq!(copilot_marker.trim(), copilot_stdout["version"]);
}

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
            "install",
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
            "install",
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

#[test]
fn copilot_package_install_preserves_existing_github_content() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let github_dir = temp.path().join(".github");
    let workflow = github_dir.join("workflows/ci.yml");
    let instructions = github_dir.join("copilot-instructions.md");
    let extension_customization = github_dir.join("extensions/kast/custom.json");
    std::fs::create_dir_all(workflow.parent().expect("workflow parent")).expect("workflow dir");
    std::fs::create_dir_all(extension_customization.parent().expect("extension parent"))
        .expect("extension dir");
    std::fs::write(&workflow, b"name: CI\n").expect("workflow");
    std::fs::write(&instructions, b"repo guidance\n").expect("instructions");
    std::fs::write(&extension_customization, b"{\"keep\":true}\n").expect("customization");
    std::fs::write(github_dir.join(".kast-copilot-version"), b"stale\n").expect("marker");

    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");

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
    assert_eq!(
        std::fs::read_to_string(&extension_customization).expect("customization"),
        "{\"keep\":true}\n"
    );
    assert_eq!(
        std::fs::read_to_string(github_dir.join(".kast-copilot-version")).expect("package marker"),
        format!("{}\n", env!("CARGO_PKG_VERSION"))
    );
    assert!(github_dir.join("lsp.json").is_file());
    assert!(
        github_dir
            .join("instructions/kast-kotlin.instructions.md")
            .is_file()
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(github_dir.join("agents/kast-reader.agent.md").is_file());
    assert!(github_dir.join("agents/kast-writer.agent.md").is_file());
    assert!(
        github_dir
            .join("extensions/kast/_shared/commands.json")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/kast-trace.mjs")
            .is_file()
    );
    assert!(
        github_dir.join("extensions/kast/custom.json").is_file(),
        "unrelated old extension customization should be preserved"
    );
}

#[test]
fn copilot_package_install_adds_managed_git_info_exclude_block() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let github_dir = repo.join(".github");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&repo).expect("repo");
    let init = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot plugin");

    assert!(
        copilot.status.success(),
        "install should write git exclude block: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(stdout["gitExclude"]["attempted"], true);
    assert_eq!(stdout["gitExclude"]["updated"], true);
    assert_eq!(
        stdout["gitExclude"]["excludeFile"],
        repo.join(".git/info/exclude").display().to_string()
    );
    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(install_manifest_path(&home)).expect("install manifest"),
    )
    .expect("manifest json");
    assert_eq!(manifest["repos"][0]["path"], repo.display().to_string());
    assert_eq!(
        manifest["repos"][0]["copilotPackageVersion"],
        env!("CARGO_PKG_VERSION")
    );

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(exclude.contains("# >>> kast copilot package >>>"));
    assert!(exclude.contains(".github/.kast-copilot-version"));
    assert!(exclude.contains(".github/lsp.json"));
    assert!(exclude.contains("# <<< kast copilot package <<<"));

    let rerun = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("reinstall copilot plugin");
    assert!(
        rerun.status.success(),
        "reinstall should be idempotent: stdout={}, stderr={}",
        String::from_utf8_lossy(&rerun.stdout),
        String::from_utf8_lossy(&rerun.stderr),
    );
    let rerun_stdout: serde_json::Value =
        serde_json::from_slice(&rerun.stdout).expect("copilot reinstall json");
    assert_eq!(rerun_stdout["gitExclude"]["attempted"], true);
    assert_eq!(rerun_stdout["gitExclude"]["updated"], false);
}

#[test]
fn copilot_package_install_can_skip_git_info_exclude() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo = temp.path().join("repo");
    let github_dir = repo.join(".github");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&repo).expect("repo");
    let init = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .arg("init")
        .output()
        .expect("git init");
    assert!(
        init.status.success(),
        "git init failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&init.stdout),
        String::from_utf8_lossy(&init.stderr)
    );

    let copilot = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "copilot",
            "--target-dir",
            github_dir.to_str().expect("github path"),
            "--no-auto-exclude-git",
        ])
        .output()
        .expect("install copilot plugin");

    assert!(
        copilot.status.success(),
        "install should support git exclude opt-out: stdout={}, stderr={}",
        String::from_utf8_lossy(&copilot.stdout),
        String::from_utf8_lossy(&copilot.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&copilot.stdout).expect("copilot install json");
    assert_eq!(stdout["gitExclude"]["attempted"], false);
    assert_eq!(stdout["gitExclude"]["updated"], false);
    assert_eq!(stdout["gitExclude"]["reason"], "disabled");

    let exclude =
        std::fs::read_to_string(repo.join(".git/info/exclude")).expect("git info exclude");
    assert!(!exclude.contains("# >>> kast copilot package >>>"));
    assert!(!exclude.contains(".github/lsp.json"));
}

#[test]
fn doctor_resolves_relative_managed_paths_under_install_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let runtime_libs = install_root.join("current/lib/backends/headless/current/runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": [],
            "managedPaths": ["current/lib/backends/headless"],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let doctor = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("doctor");

    assert!(
        doctor.status.success(),
        "doctor should treat relative managed paths as install-root-relative: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&doctor.stdout).expect("doctor json");
    assert_eq!(stdout["ok"], true, "{stdout}");
    assert_eq!(stdout["configuration"]["valid"], true, "{stdout}");
    assert_eq!(
        stdout["canonicalDirectory"]["root"],
        install_root.display().to_string(),
        "{stdout}"
    );
    assert_eq!(stdout["binary"]["configuredExists"], true, "{stdout}");
    assert_eq!(
        stdout["binary"]["configuredMatchesRunning"], true,
        "{stdout}"
    );
    assert!(
        !stdout["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning
                .as_str()
                .expect("warning")
                .contains("Managed path is missing")),
        "{stdout}"
    );
}

#[test]
fn doctor_reports_invalid_config_without_crashing() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(config_home.join("config.toml"), "[paths\ninstallRoot =")
        .expect("invalid config");

    let doctor = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("doctor");

    assert!(
        !doctor.status.success(),
        "doctor should return unhealthy status for invalid config: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&doctor.stdout).expect("doctor json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["configuration"]["exists"], true, "{stdout}");
    assert_eq!(stdout["configuration"]["valid"], false, "{stdout}");
    assert!(
        stdout["configuration"]["error"]
            .as_str()
            .expect("configuration error")
            .contains("Config is invalid"),
        "{stdout}"
    );
    assert!(
        stdout["issues"]
            .as_array()
            .expect("issues")
            .iter()
            .any(|issue| issue.as_str().expect("issue").contains("Config is invalid")),
        "{stdout}"
    );
}

#[test]
fn doctor_flags_installed_backend_below_embedded_minimum() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = default_install_root(&home);
    let install_dir = install_root.join("current/lib/backends/headless/headless-0.0.1");
    let runtime_libs = install_dir.join("runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::create_dir_all(
        install_manifest_path(&home)
            .parent()
            .expect("manifest parent"),
    )
    .expect("manifest parent");
    std::fs::write(
        install_manifest_path(&home),
        serde_json::to_string_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "test-install",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(&home).display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": env!("CARGO_BIN_EXE_kast"),
                "activeBinary": env!("CARGO_BIN_EXE_kast")
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["backend:headless"],
            "managedPaths": ["current/lib/backends/headless"],
            "backends": [{
                "name": "headless",
                "version": "0.0.1",
                "installDir": install_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("manifest json"),
    )
    .expect("manifest");

    let doctor = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
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

    assert!(skill.contains("Rust `kast` CLI"));
    assert!(skill.contains("command -v kast"));
    assert!(skill.contains("Use for Gradle project file work"));
    assert!(skill.contains("Default to Kast"));
    assert!(skill.contains("project file operations"));
    assert!(skill.contains("Use Kast to discover the owning module"));
    assert!(skill.contains("when the path is not already exact"));
    assert!(skill.contains("Unknown symbol"));
    assert!(skill.contains("symbol/query"));
    assert!(skill.contains("raw/workspace-files"));
    assert!(skill.contains("includeFiles=false"));
    assert!(skill.contains("kast metrics fan-in"));
    assert!(skill.contains("kast demo"));
    assert!(skill.contains("raw/type-hierarchy"));
    assert!(skill.contains("raw/semantic-insertion-point"));
    assert!(skill.contains("raw/completions"));
    assert!(skill.contains("raw/apply-edits"));
    assert!(skill.contains("kast up --workspace-root \"$PWD\" --backend idea"));
    assert!(quickstart.contains("command -v kast"));
    assert!(quickstart.contains("kast agent call"));
    assert!(quickstart.contains("raw transport/debug escape hatch"));
    assert!(quickstart.contains("kast metrics impact"));
    assert!(quickstart.contains("kast demo"));
    assert!(quickstart.contains("INDEX_UNAVAILABLE"));
    assert!(quickstart.contains("kast up --workspace-root \"$PWD\" --backend idea"));
    assert!(routing_reference.contains("rust-kast-cli"));

    assert!(
        root.join("resources/plugin/lsp.json").is_file(),
        "packaged Copilot LSP plugin source must live under cli-rs/resources/plugin"
    );
}

#[test]
fn repo_local_copilot_plugin_content_is_generated_not_tracked() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");

    assert!(
        root.join("cli-rs/resources/plugin/plugin.json").is_file(),
        "repo-local plugin source must live under cli-rs/resources/plugin"
    );
}
