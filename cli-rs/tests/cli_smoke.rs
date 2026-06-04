use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

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

fn write_devin_cli_archive(root: &Path, version: &str) -> PathBuf {
    let staging = root.join("devin-cli-staging");
    let archive = root.join(format!("kast-{version}-linux-x64.zip"));
    std::fs::create_dir_all(&staging).expect("cli staging");
    let cli = staging.join("kast");
    std::fs::write(
        &cli,
        r#"#!/usr/bin/env bash
set -euo pipefail

case "${1:-help}" in
  doctor)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    [[ -f "${KAST_CONFIG_HOME}/config.toml" ]] || { echo "missing config.toml" >&2; exit 1; }
    grep -Fq "[runtime]" "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq 'defaultBackend = "headless"' "${KAST_CONFIG_HOME}/config.toml"
    grep -Fq "[backends.headless]" "${KAST_CONFIG_HOME}/config.toml"
    printf '%s\n' '{"ok":true}'
    ;;
  up)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    for arg in "$@"; do
      case "$arg" in
        --backend|--backend=*)
          echo "verify command must not pass --backend to up" >&2
          exit 1
          ;;
      esac
    done
    touch "${KAST_CONFIG_HOME}/up-called"
    printf '%s\n' '{"selected":{"descriptor":{"backendName":"headless"},"runtimeStatus":{"backendName":"headless"}}}'
    ;;
  rpc)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    for arg in "$@"; do
      case "$arg" in
        --backend|--backend=*)
          echo "verify command must not pass --backend to rpc" >&2
          exit 1
          ;;
      esac
    done
    [[ "${2:-}" == *'"method":"runtime/status"'* ]] || { echo "unexpected rpc request: ${2:-}" >&2; exit 1; }
    touch "${KAST_CONFIG_HOME}/rpc-called"
    printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"backendName":"headless"}}'
    ;;
  stop)
    [[ -n "${KAST_CONFIG_HOME:-}" ]] || { echo "missing KAST_CONFIG_HOME" >&2; exit 1; }
    touch "${KAST_CONFIG_HOME}/stop-called"
    printf '%s\n' '{"stopped":true}'
    ;;
  version|--version)
    printf '%s\n' "Kast CLI 9.8.7"
    ;;
  *)
    printf '%s\n' "fake kast"
    ;;
esac
"#,
    )
    .expect("fake devin cli");
    set_executable(&cli);
    zip_dir(&archive, &staging);
    archive
}

fn write_devin_backend_archive(root: &Path, version: &str, stale: bool, fat_jar: bool) -> PathBuf {
    let suffix = match (stale, fat_jar) {
        (true, _) => "stale",
        (_, true) => "fat",
        _ => "ok",
    };
    let staging = root.join(format!("devin-backend-{suffix}-staging"));
    let archive = root.join(format!("backend-headless-{suffix}-{version}.zip"));
    let backend_root = staging.join("backend-headless");
    let runtime_libs = backend_root.join("runtime-libs");
    let plugin_libs = backend_root.join("idea-home/plugins/kast-headless/lib");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::create_dir_all(backend_root.join("idea-home/lib")).expect("idea lib");
    std::fs::create_dir_all(backend_root.join("idea-home/modules")).expect("idea modules");
    std::fs::create_dir_all(&plugin_libs).expect("plugin libs");

    let archive_version = if stale {
        "1.2.3"
    } else {
        version.trim_start_matches('v')
    };
    std::fs::write(
        runtime_libs.join("classpath.txt"),
        format!("backend-headless-{archive_version}-launcher.jar\n"),
    )
    .expect("classpath");
    std::fs::write(
        runtime_libs.join(format!("backend-headless-{archive_version}-launcher.jar")),
        b"launcher",
    )
    .expect("launcher jar");
    std::fs::write(
        plugin_libs.join(format!("backend-headless-{archive_version}-plugin.jar")),
        b"plugin",
    )
    .expect("plugin jar");
    std::fs::write(backend_root.join("idea-home/lib/nio-fs.jar"), b"nio").expect("nio");
    std::fs::write(
        backend_root.join("idea-home/modules/module-descriptors.dat"),
        b"modules",
    )
    .expect("module descriptors");
    let launcher = backend_root.join("kast-headless");
    std::fs::write(&launcher, "#!/usr/bin/env bash\n").expect("headless launcher");
    set_executable(&launcher);
    if fat_jar {
        std::fs::create_dir_all(backend_root.join("libs")).expect("libs");
        std::fs::write(
            backend_root.join("libs/backend-headless-9.8.7-all.jar"),
            b"fat jar",
        )
        .expect("fat jar");
    }

    zip_dir(&archive, &staging);
    archive
}

fn set_executable(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = std::fs::metadata(path).expect("metadata").permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(path, permissions).expect("chmod");
    }
}

fn zip_dir(archive: &Path, input_dir: &Path) {
    let status = Command::new("zip")
        .args(["-qr", archive.to_str().expect("archive path"), "."])
        .current_dir(input_dir)
        .status()
        .expect("zip command");
    assert!(status.success(), "zip command should create {archive:?}");
    assert!(archive.is_file(), "zip archive should exist: {archive:?}");
}

fn extract_tar_gz(archive: &Path, output_dir: &Path) {
    std::fs::create_dir_all(output_dir).expect("extract dir");
    let status = Command::new("tar")
        .args([
            "-xzf",
            archive.to_str().expect("archive path"),
            "-C",
            output_dir.to_str().expect("extract path"),
        ])
        .status()
        .expect("tar command");
    assert!(status.success(), "tar command should extract {archive:?}");
}

struct TestHttpServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl TestHttpServer {
    fn base_url(&self) -> String {
        format!("http://{}", self.addr)
    }
}

impl Drop for TestHttpServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            handle.join().expect("test http server join");
        }
    }
}

fn serve_directory(root: PathBuf) -> TestHttpServer {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind test http server");
    listener
        .set_nonblocking(true)
        .expect("nonblocking test http server");
    let addr = listener.local_addr().expect("test http server addr");
    let stop = Arc::new(AtomicBool::new(false));
    let thread_stop = Arc::clone(&stop);
    let handle = thread::spawn(move || {
        while !thread_stop.load(Ordering::SeqCst) {
            match listener.accept() {
                Ok((stream, _)) => serve_http_file(stream, &root),
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                }
                Err(error) => panic!("test http server accept failed: {error}"),
            }
        }
    });
    TestHttpServer {
        addr,
        stop,
        handle: Some(handle),
    }
}

fn serve_http_file(mut stream: TcpStream, root: &Path) {
    stream
        .set_nonblocking(false)
        .expect("blocking test connection");
    let mut buffer = [0_u8; 4096];
    let read = stream.read(&mut buffer).expect("read test request");
    let request = String::from_utf8_lossy(&buffer[..read]);
    let path = request
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .unwrap_or("/");
    let path = path.trim_start_matches('/');
    let file = root.join(path);
    if file.is_file() {
        let bytes = std::fs::read(file).expect("read served fixture");
        let header = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            bytes.len()
        );
        stream
            .write_all(header.as_bytes())
            .expect("write response header");
        stream.write_all(&bytes).expect("write response body");
    } else {
        stream
            .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .expect("write not found");
    }
}

fn sha256_file(path: &Path) -> String {
    let mut hasher = Sha256::new();
    hasher.update(std::fs::read(path).expect("asset bytes"));
    hex::encode(hasher.finalize())
}

fn write_backend_release_asset(root: &Path, backend: &str, tag: &str) -> String {
    let archive = write_backend_archive(root, backend, tag);
    let asset_name = format!("kast-{backend}-{tag}.zip");
    std::fs::copy(&archive, root.join(&asset_name)).expect("copy release backend asset");
    asset_name
}

fn write_backend_release_metadata(
    root: &Path,
    platform_id: &str,
    asset_name: &str,
    checksum_override: Option<&str>,
    provenance_digest_override: Option<&str>,
    include_sha256sums: bool,
    include_provenance: bool,
) {
    let digest = sha256_file(&root.join(asset_name));
    if include_sha256sums {
        std::fs::write(
            root.join("SHA256SUMS"),
            format!(
                "{}  {asset_name}\n",
                checksum_override.unwrap_or(digest.as_str())
            ),
        )
        .expect("write SHA256SUMS");
    }
    if include_provenance {
        std::fs::write(root.join("build-provenance.json"), {
            let provenance_digest = provenance_digest_override
                .map(str::to_string)
                .unwrap_or_else(|| format!("sha256:{digest}"));
            format!(
                r#"{{
  "builds": [
    {{
      "platformId": "{platform_id}",
      "assetName": "{asset_name}",
      "assetDigest": "{provenance_digest}"
    }}
  ]
}}
"#
            )
        })
        .expect("write build provenance");
    }
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
fn backend_install_downloaded_archive_verifies_release_metadata() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "standalone", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "standalone",
        &asset_name,
        None,
        None,
        true,
        true,
    );
    let server = serve_directory(release_dir);
    let base_url = server.base_url();

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("backend install");

    assert!(
        install.status.success(),
        "verified backend install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("backend install json");
    assert_eq!(stdout["downloaded"], true);
    assert!(
        home.join(".kast/lib/backends/current/runtime-libs/classpath.txt")
            .is_file()
    );
}

#[test]
fn backend_install_downloaded_archive_rejects_checksum_mismatch_before_extract() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "standalone", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "standalone",
        &asset_name,
        Some("0000000000000000000000000000000000000000000000000000000000000000"),
        None,
        true,
        true,
    );
    let server = serve_directory(release_dir);
    let base_url = server.base_url();

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("backend install");

    assert!(
        !install.status.success(),
        "checksum mismatch should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("\"code\": \"BACKEND_RELEASE_VERIFY_FAILED\""),
        "{stderr}"
    );
    assert!(!home.join(".kast/lib/backends/current").exists());
}

#[test]
fn backend_install_downloaded_archive_rejects_provenance_digest_mismatch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "standalone", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "standalone",
        &asset_name,
        None,
        Some("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
        true,
        true,
    );
    let server = serve_directory(release_dir);
    let base_url = server.base_url();

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("backend install");

    assert!(
        !install.status.success(),
        "provenance mismatch should fail: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("\"code\": \"BACKEND_RELEASE_VERIFY_FAILED\""),
        "{stderr}"
    );
    assert!(!home.join(".kast/lib/backends/current").exists());
}

#[test]
fn backend_install_downloaded_archive_requires_sha256sums() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "standalone", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "standalone",
        &asset_name,
        None,
        None,
        false,
        true,
    );
    let server = serve_directory(release_dir);
    let base_url = server.base_url();

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("backend install");

    assert!(!install.status.success());
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("\"code\": \"BACKEND_DOWNLOAD_FAILED\""),
        "{stderr}"
    );
    assert!(!home.join(".kast/lib/backends/current").exists());
}

#[test]
fn backend_install_downloaded_archive_requires_provenance() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "standalone", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "standalone",
        &asset_name,
        None,
        None,
        true,
        false,
    );
    let server = serve_directory(release_dir);
    let base_url = server.base_url();

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "standalone",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("backend install");

    assert!(!install.status.success());
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("\"code\": \"BACKEND_DOWNLOAD_FAILED\""),
        "{stderr}"
    );
    assert!(!home.join(".kast/lib/backends/current").exists());
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
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed headless backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(
        stderr.contains("kast backend install headless"),
        "stderr should include configured default install command: {stderr}"
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
            "--backend=standalone",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "up should fail without an installed standalone backend"
    );
    let stderr = String::from_utf8_lossy(&up.stderr);
    assert!(
        stderr.contains("kast backend install standalone"),
        "stderr should include explicit backend install command: {stderr}"
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
        stderr.contains("kast backend install headless"),
        "stderr should include configured default install command: {stderr}"
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
            "--backend=standalone",
        ])
        .output()
        .expect("rpc");

    assert!(
        !rpc.status.success(),
        "rpc should fail without an installed standalone backend"
    );
    let stderr = String::from_utf8_lossy(&rpc.stderr);
    assert!(
        stderr.contains("kast backend install standalone"),
        "stderr should include explicit backend install command: {stderr}"
    );
}

#[test]
fn devin_runtime_package_setup_and_verify_use_cli_owned_contract() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let artifacts = temp.path().join("artifacts");
    let extract = temp.path().join("extract");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&artifacts).expect("artifacts");

    let version = "v9.8.7";
    let cli_archive = write_devin_cli_archive(temp.path(), version);
    let backend_archive = write_devin_backend_archive(temp.path(), version, false, false);
    let output = artifacts.join(format!(
        "kast-devin-headless-runtime-linux-x64-{version}.tar.gz"
    ));

    let package = kast(&home, &config_home)
        .args([
            "devin-runtime",
            "package",
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--version",
            version,
            "--output",
            output.to_str().expect("output"),
        ])
        .output()
        .expect("devin runtime package");

    assert!(
        package.status.success(),
        "package should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&package.stdout),
        String::from_utf8_lossy(&package.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&package.stdout).expect("package json");
    assert_eq!(stdout["platform"], "devin-headless-linux-x64");
    assert_eq!(stdout["backendInstallName"], format!("headless-{version}"));
    assert!(output.is_file(), "bundle archive should exist");
    let checksum = PathBuf::from(format!("{}.sha256", output.display()));
    assert!(checksum.is_file(), "checksum sidecar should exist");

    extract_tar_gz(&output, &extract);
    let bundle_root = extract.join(format!("kast-devin-headless-runtime-linux-x64-{version}"));
    let backend_root = bundle_root.join(format!("lib/backends/headless-{version}"));
    assert!(bundle_root.join("bin/kast").is_file());
    assert!(backend_root.join("runtime-libs/classpath.txt").is_file());
    assert!(
        backend_root
            .join("idea-home/plugins/kast-headless")
            .is_dir()
    );
    assert!(!bundle_root.join("config.toml").exists());

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(bundle_root.join("manifest.json")).expect("manifest"),
    )
    .expect("manifest json");
    assert_eq!(manifest["kind"], "KAST_DEVIN_HEADLESS_RUNTIME");
    assert_eq!(manifest["platform"], "devin-headless-linux-x64");
    assert_eq!(
        manifest["backendInstallName"],
        format!("headless-{version}")
    );

    let setup = kast(&home, &config_home)
        .args([
            "devin-runtime",
            "setup",
            "--prefix",
            bundle_root.to_str().expect("bundle root"),
        ])
        .output()
        .expect("devin runtime setup");
    assert!(
        setup.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let config = std::fs::read_to_string(bundle_root.join("config.toml")).expect("config");
    assert!(config.contains("[runtime]"), "{config}");
    assert!(config.contains("defaultBackend = \"headless\""), "{config}");
    assert!(
        config.contains(&format!(
            "runtimeLibsDir = \"{}\"",
            backend_root.join("runtime-libs").display()
        )),
        "{config}"
    );
    assert!(
        config.contains(&format!(
            "binaryPath = \"{}\"",
            bundle_root.join("bin/kast").display()
        )),
        "{config}"
    );

    let verify = kast(&home, &config_home)
        .args([
            "devin-runtime",
            "verify",
            "--prefix",
            bundle_root.to_str().expect("bundle root"),
        ])
        .output()
        .expect("devin runtime verify");
    assert!(
        verify.status.success(),
        "verify should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let verify_stdout: serde_json::Value =
        serde_json::from_slice(&verify.stdout).expect("verify json");
    assert_eq!(verify_stdout["backendName"], "headless");
    assert!(bundle_root.join("up-called").is_file());
    assert!(bundle_root.join("rpc-called").is_file());
    assert!(bundle_root.join("stop-called").is_file());
}

#[test]
fn devin_runtime_package_rejects_stale_backend_archive() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let version = "v9.8.7";
    let cli_archive = write_devin_cli_archive(temp.path(), version);
    let backend_archive = write_devin_backend_archive(temp.path(), version, true, false);
    let output = temp.path().join("must-not-exist.tar.gz");

    let package = kast(&home, &config_home)
        .args([
            "devin-runtime",
            "package",
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--version",
            version,
            "--output",
            output.to_str().expect("output"),
        ])
        .output()
        .expect("devin runtime package");

    assert!(!package.status.success(), "stale backend should fail");
    let stderr = String::from_utf8_lossy(&package.stderr);
    assert!(
        stderr.contains("does not match requested version v9.8.7"),
        "{stderr}"
    );
    assert!(!output.exists(), "failed package must not create output");
}

#[test]
fn devin_runtime_package_rejects_headless_fat_jars() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let version = "v9.8.7";
    let cli_archive = write_devin_cli_archive(temp.path(), version);
    let backend_archive = write_devin_backend_archive(temp.path(), version, false, true);
    let output = temp.path().join("must-not-exist.tar.gz");

    let package = kast(&home, &config_home)
        .args([
            "devin-runtime",
            "package",
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--version",
            version,
            "--output",
            output.to_str().expect("output"),
        ])
        .output()
        .expect("devin runtime package");

    assert!(!package.status.success(), "fat jar backend should fail");
    let stderr = String::from_utf8_lossy(&package.stderr);
    assert!(stderr.contains("must not contain fat jars"), "{stderr}");
    assert!(!output.exists(), "failed package must not create output");
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
