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

    let github_dir = temp.path().join(".github");
    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot-extension",
            "--target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("install copilot extension");
    assert!(copilot.status.success());
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        github_dir
            .join("extensions/kast/kotlin-gradle-loop/tools.mjs")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/.kast-copilot-version")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/commands.json")
            .is_file()
    );
    assert!(!github_dir.join(".kast-copilot-version").exists());
    assert!(!github_dir.join("extensions/_shared").exists());
    assert!(!github_dir.join("extensions/kotlin-gradle-loop").exists());
    assert!(
        !github_dir.join("hooks").exists(),
        "copilot extension install must not write .github/hooks integration"
    );

    let verify = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(temp.path())
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .arg("verify-extension")
        .output()
        .expect("verify extension");
    assert!(
        verify.status.success(),
        "verify-extension should read the marker inside extensions/kast: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
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
    let asset_name = write_backend_release_asset(&release_dir, "headless", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "headless",
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
            "headless",
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
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
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
    let asset_name = write_backend_release_asset(&release_dir, "headless", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "headless",
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
            "headless",
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
    assert!(!home.join(".kast/lib/backends/headless/current").exists());
}

#[test]
fn backend_install_downloaded_archive_rejects_provenance_digest_mismatch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "headless", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "headless",
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
            "headless",
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
    assert!(!home.join(".kast/lib/backends/headless/current").exists());
}

#[test]
fn backend_install_downloaded_archive_requires_sha256sums() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "headless", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "headless",
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
            "headless",
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
    assert!(!home.join(".kast/lib/backends/headless/current").exists());
}

#[test]
fn backend_install_downloaded_archive_requires_provenance() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let release_dir = temp.path().join("release");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let asset_name = write_backend_release_asset(&release_dir, "headless", "v9.8.7");
    write_backend_release_metadata(
        &release_dir,
        "headless",
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
            "headless",
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
    assert!(!home.join(".kast/lib/backends/headless/current").exists());
}

#[test]
fn backend_install_headless_archive_configures_runtime_and_install_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let archive = write_backend_archive(temp.path(), "headless", "v9.8.7");

    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "headless",
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
    assert_eq!(stdout["backendName"], "headless");
    assert_eq!(stdout["version"], "v9.8.7");
    assert_eq!(stdout["downloaded"], false);
    assert!(
        stdout["runtimeLibsDir"]
            .as_str()
            .unwrap()
            .ends_with(".kast/lib/backends/headless/current/runtime-libs")
    );

    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("[install]"), "{config}");
    assert!(config.contains("[[install.backends]]"), "{config}");
    assert!(config.contains("name = \"headless\""), "{config}");
    assert!(config.contains("version = \"v9.8.7\""), "{config}");
    assert!(config.contains("\"backend:headless\""), "{config}");
    assert!(
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .is_file()
    );
}

#[test]
fn backend_uninstall_removes_headless_component() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let archive = write_backend_archive(temp.path(), "headless", "v9.8.7");
    let install = kast(&home, &config_home)
        .args([
            "backend",
            "install",
            "headless",
            "--archive",
            archive.to_str().expect("archive path"),
            "--version",
            "v9.8.7",
        ])
        .output()
        .expect("backend install");
    assert!(
        install.status.success(),
        "install headless: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );

    let uninstall = kast(&home, &config_home)
        .args(["backend", "uninstall", "headless"])
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
    assert_eq!(stdout["backendName"], "headless");
    assert_eq!(stdout["skipped"], false);

    assert!(!home.join(".kast/lib/backends/headless/current").exists());
    assert!(
        !home
            .join(".kast/lib/backends/headless/headless-v9.8.7")
            .exists()
    );
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(!config.contains("[install]"), "{config}");
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
            "--backend=headless",
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
        stderr.contains("kast backend install headless"),
        "stderr should include explicit backend install command: {stderr}"
    );
}

#[test]
fn idea_plugin_install_defaults_to_downloads_without_jetbrains_profiles() {
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
        .args(["install", "idea-plugin", "--dry-run"])
        .output()
        .expect("install idea plugin");

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
fn idea_plugin_link_flag_uses_profile_install_mode() {
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
            "install",
            "idea-plugin",
            "--link-jetbrains-profiles",
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
fn copilot_extension_install_preserves_existing_github_content() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let github_dir = temp.path().join(".github");
    let workflow = github_dir.join("workflows/ci.yml");
    let instructions = github_dir.join("copilot-instructions.md");
    let legacy_hooks_dir = github_dir.join("hooks");
    let extension_customization = github_dir.join("extensions/kast/custom.json");
    std::fs::create_dir_all(workflow.parent().expect("workflow parent")).expect("workflow dir");
    std::fs::create_dir_all(&legacy_hooks_dir).expect("legacy hooks dir");
    std::fs::create_dir_all(extension_customization.parent().expect("extension parent"))
        .expect("extension dir");
    std::fs::write(&workflow, b"name: CI\n").expect("workflow");
    std::fs::write(&instructions, b"repo guidance\n").expect("instructions");
    std::fs::write(legacy_hooks_dir.join("hooks.json"), b"{\"version\":1}\n")
        .expect("legacy hooks");
    std::fs::write(
        legacy_hooks_dir.join("session-start.sh"),
        b"#!/usr/bin/env bash\n",
    )
    .expect("legacy session hook");
    std::fs::write(&extension_customization, b"{\"keep\":true}\n").expect("customization");
    std::fs::write(github_dir.join(".kast-copilot-version"), b"stale\n").expect("marker");

    let copilot = kast(&home, &config_home)
        .args([
            "install",
            "copilot-extension",
            "--target-dir",
            github_dir.to_str().expect("github path"),
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
    assert_eq!(
        std::fs::read_to_string(legacy_hooks_dir.join("hooks.json")).expect("legacy hooks"),
        "{\"version\":1}\n"
    );
    assert_eq!(
        std::fs::read_to_string(legacy_hooks_dir.join("session-start.sh"))
            .expect("legacy session hook"),
        "#!/usr/bin/env bash\n"
    );
    assert_eq!(
        std::fs::read_to_string(&extension_customization).expect("customization"),
        "{\"keep\":true}\n"
    );
    assert_eq!(
        std::fs::read_to_string(github_dir.join(".kast-copilot-version")).expect("legacy marker"),
        "stale\n"
    );
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        github_dir
            .join("extensions/kast/.kast-copilot-version")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/_shared/commands.json")
            .is_file()
    );
    assert!(
        github_dir
            .join("extensions/kast/kotlin-gradle-loop/tools.mjs")
            .is_file()
    );
    assert!(!github_dir.join("extensions/_shared").exists());
    assert!(!github_dir.join("extensions/kotlin-gradle-loop").exists());
    assert!(!github_dir.join("agents").exists());
    let extension = std::fs::read_to_string(github_dir.join("extensions/kast/extension.mjs"))
        .expect("kast extension");
    assert!(
        !extension.contains("hooks:"),
        "kast extension must not register Copilot SDK hooks"
    );
}

#[test]
fn doctor_resolves_relative_managed_paths_under_install_root() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = home.join(".kast");
    let runtime_libs = install_root.join("backends/headless/headless-0.0.1/runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
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
    let runtime_libs = install_root.join("backends/headless/headless-0.0.1/runtime-libs");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&runtime_libs).expect("runtime libs");
    std::fs::write(runtime_libs.join("classpath.txt"), "kast-test.jar\n").expect("classpath");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"

[install]
version = "0.1.0"
components = ["backend:headless"]
managedPaths = ["backends/headless"]
schemaVersion = 3

[[install.backends]]
name = "headless"
version = "0.0.1"
installDir = "{}"
runtimeLibsDir = "{}"
"#,
            install_root.display(),
            install_root
                .join("backends/headless/headless-0.0.1")
                .display(),
            runtime_libs.display()
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
        "resources/copilot-extension/extensions/kast/extension.mjs",
    ] {
        let content = std::fs::read_to_string(root.join(relative)).expect(relative);
        assert!(
            !content.contains("kast-cli"),
            "{relative} must not resolve or advertise the deleted JVM CLI",
        );
    }
    assert!(
        !root.join("resources/copilot-extension/hooks").exists(),
        "packaged Copilot extension must not include command-hook integration"
    );
    let kast_extension = std::fs::read_to_string(
        root.join("resources/copilot-extension/extensions/kast/extension.mjs"),
    )
    .expect("kast extension");
    assert!(
        !kast_extension.contains("hooks:"),
        "packaged Kast extension must not register Copilot SDK hooks"
    );
}
