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
elif [ "$1" = "fetch" ] && [ "$2" = "--cask" ]; then
  cache="${{HOME:-/tmp}}/000--kast-plugin.zip"
  printf 'fake plugin zip\n' > "$cache"
  printf 'fake brew fetched kast plugin\n' >&2
elif [ "$1" = "--cache" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{HOME:-/tmp}}/000--kast-plugin.zip"
elif [ "$1" = "install" ] && [ "$2" = "--cask" ]; then
  printf 'fake brew installed kast plugin\n' >&2
elif [ "$1" = "reinstall" ] && [ "$2" = "--cask" ]; then
  printf 'fake brew reinstalled kast plugin\n' >&2
elif [ "$1" = "list" ] && [ "$2" = "--cask" ]; then
  if [ "${{KAST_FAKE_BREW_CASK_VERSION:-}}" != "" ]; then
    printf 'kast-plugin %s\n' "$KAST_FAKE_BREW_CASK_VERSION"
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

fn write_fake_java(home: &Path, log_file: &Path) -> PathBuf {
    let java_home = home.join("fake-java-home");
    let java = java_home.join("bin/java");
    std::fs::create_dir_all(java.parent().expect("java bin")).expect("java bin");
    std::fs::write(
        &java,
        format!(
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > {}\nexit 0\n",
            shell_quote(log_file)
        ),
    )
    .expect("fake java");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = std::fs::metadata(&java)
            .expect("java metadata")
            .permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&java, permissions).expect("java mode");
    }
    java_home
}

fn shell_quote(path: &Path) -> String {
    format!("'{}'", path.display().to_string().replace('\'', "'\\''"))
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

    let install_help = kast(&home, &config_home)
        .args(["install", "--help"])
        .output()
        .expect("install help");
    assert!(install_help.status.success());
    let install_help_stdout = String::from_utf8_lossy(&install_help.stdout);
    for command in [
        "plugin",
        "headless",
        "skill",
        "copilot",
        "shell",
        "completion",
    ] {
        assert!(
            install_help_stdout.contains(command),
            "install help should list {command}: {install_help_stdout}"
        );
    }
    for command in ["plugin", "headless", "skill", "copilot"] {
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
        if command == "headless" {
            assert!(
                stdout.contains("--insecure-skip-tls-verify"),
                "install headless help should expose the enterprise TLS escape hatch: {stdout}"
            );
        }
        assert!(
            !stdout.contains("--yes"),
            "install {command} help should not expose deprecated --yes: {stdout}"
        );
        assert!(
            !stdout.contains("--link-name"),
            "install {command} help should not expose deprecated --link-name: {stdout}"
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
    assert!(!demo_help_stdout.contains("--no-fallback"));

    let repair = kast(&home, &config_home)
        .args(["install", "affected", "--apply"])
        .output()
        .expect("install affected");
    assert!(repair.status.success());
    assert!(config_home.join("config.toml").is_file());

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
            .join("kast/scripts/validate-rpc-request.py")
            .is_file()
    );
    assert!(skill_dir.join("kast/scripts/resolve-kast.sh").is_file());

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
fn top_level_help_hides_recovery_and_internal_install_surfaces() {
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
    for command in ["up", "status", "install", "setup", "doctor"] {
        assert!(
            stdout
                .lines()
                .any(|line| line.trim_start().starts_with(command)),
            "top-level help should show {command}: {stdout}"
        );
    }
    for hidden in [
        "config",
        "daemon",
        "backend",
        "current",
        "info",
        "verify-extension",
        "uninstall",
    ] {
        assert!(
            !stdout
                .lines()
                .any(|line| line.trim_start().starts_with(hidden)),
            "top-level help should hide {hidden}: {stdout}"
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
    for hidden in [
        "--idea-home",
        "--wait-timeout-ms",
        "--accept-indexing",
        "--no-auto-start",
        "--socket-path",
        "--module-name",
        "--source-roots",
        "--classpath",
        "--request-timeout-ms",
        "--max-results",
        "--max-concurrent-requests",
        "--profile",
        "--profile-modes",
        "--profile-duration",
        "--profile-otlp-endpoint",
    ] {
        assert!(
            !up_help_stdout.contains(hidden),
            "up help should hide low-level flag {hidden}: {up_help_stdout}"
        );
    }

    let install_help = kast(&home, &config_home)
        .args(["install", "--help"])
        .output()
        .expect("install help");
    assert!(install_help.status.success());
    let install_stdout = String::from_utf8_lossy(&install_help.stdout);
    assert!(
        install_stdout.contains("affected"),
        "install help should show the repair command: {install_stdout}"
    );
    assert!(
        !install_stdout.contains("--archive"),
        "install help should hide archive install internals: {install_stdout}"
    );
    assert!(
        !install_stdout.contains("portable archive"),
        "install help should not describe retired portable archive flow: {install_stdout}"
    );
}

#[test]
fn stale_hidden_top_level_commands_are_removed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    for command in [
        "config",
        "daemon",
        "backend",
        "current",
        "info",
        "verify-extension",
        "uninstall",
    ] {
        let output = kast(&home, &config_home)
            .args([command, "--help"])
            .output()
            .unwrap_or_else(|error| panic!("{command} --help: {error}"));
        assert!(
            !output.status.success(),
            "stale top-level command {command} should be removed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
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
    let install_root = temp.path().join("kast-install");
    let profile = temp.path().join(".zshrc");
    let empty_path = temp.path().join("empty-path");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config");
    std::fs::create_dir_all(&empty_path).expect("empty path");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"
"#,
            install_root.display()
        ),
    )
    .expect("config");

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
    assert_eq!(
        stdout["binDir"],
        install_root.join("bin").display().to_string()
    );
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
            shell_single_quote(&install_root.join("bin").display().to_string())
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
        .args(["help", "install", "headless"])
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
        stdout.contains("Install the headless JVM backend"),
        "selected help should include the command description: {stdout}"
    );
    assert!(
        stdout.contains("--archive"),
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
        stdout.starts_with("# Kast status\n"),
        "status should default to a readable Markdown-style summary: {stdout}"
    );
    assert!(
        stdout.contains("No runtime candidates were found."),
        "status should include an actionable empty-state message: {stdout}"
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
            "--output",
            "json",
            "install",
            "headless",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
            "--insecure-skip-tls-verify",
        ])
        .output()
        .expect("install headless");

    assert!(
        install.status.success(),
        "verified headless install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install headless json");
    assert_eq!(stdout["downloaded"], true);
    assert!(
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .is_file()
    );
}

#[test]
fn up_auto_installs_missing_headless_backend_before_startup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let release_dir = temp.path().join("release");
    let java_log = temp.path().join("java.log");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&release_dir).expect("release dir");
    let java_home = write_fake_java(temp.path(), &java_log);
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

    let up = kast(&home, &config_home)
        .env("JAVA_HOME", &java_home)
        .args([
            "up",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--backend=headless",
            "--install-version",
            "v9.8.7",
            "--install-base-url",
            &base_url,
            "--wait-timeout-ms",
            "1",
        ])
        .output()
        .expect("up");

    assert!(
        !up.status.success(),
        "fake daemon should time out after auto-install: stdout={}, stderr={}",
        String::from_utf8_lossy(&up.stdout),
        String::from_utf8_lossy(&up.stderr)
    );
    assert!(
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .is_file(),
        "up should install the missing headless backend before startup"
    );
    for _ in 0..100 {
        if java_log.is_file() {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    panic!("up should reach the Java spawn boundary after installing the backend");
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
            "--output",
            "json",
            "install",
            "headless",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("install headless");

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
            "--output",
            "json",
            "install",
            "headless",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("install headless");

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
            "--output",
            "json",
            "install",
            "headless",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("install headless");

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
            "--output",
            "json",
            "install",
            "headless",
            "--version",
            "v9.8.7",
            "--base-url",
            &base_url,
        ])
        .output()
        .expect("install headless");

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
            "--output",
            "json",
            "install",
            "headless",
            "--archive",
            archive.to_str().expect("archive path"),
            "--version",
            "v9.8.7",
        ])
        .output()
        .expect("install headless");

    assert!(
        install.status.success(),
        "install headless should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install headless json");
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
fn install_headless_gateway_and_doctor_report_installed_version() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");
    let archive = write_backend_archive(temp.path(), "headless", "v9.8.7");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "headless",
            "--archive",
            archive.to_str().expect("archive path"),
            "--version",
            "v9.8.7",
            "--force",
        ])
        .output()
        .expect("install headless");

    assert!(
        install.status.success(),
        "install headless gateway should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("install headless json");
    assert_eq!(stdout["backendName"], "headless");
    assert_eq!(stdout["version"], "v9.8.7");

    let doctor = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("doctor");
    assert!(
        doctor.status.success(),
        "doctor should include installed component versions: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr)
    );
    let doctor_stdout: serde_json::Value =
        serde_json::from_slice(&doctor.stdout).expect("doctor json");
    assert_eq!(doctor_stdout["ok"], true);
    assert_eq!(
        doctor_stdout["install"]["version"],
        env!("CARGO_PKG_VERSION")
    );
    assert_eq!(doctor_stdout["install"]["backends"][0]["name"], "headless");
    assert_eq!(doctor_stdout["install"]["backends"][0]["version"], "v9.8.7");
}

#[test]
fn setup_skip_headless_keeps_clean_machine_backend_free() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let skill_dir = temp.path().join("skills");
    let github_dir = temp.path().join(".github");
    std::fs::create_dir_all(&home).expect("home");

    let setup = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "setup",
            "--skip-headless",
            "--skip-shell",
            "--include-skill",
            "--skill-target-dir",
            skill_dir.to_str().expect("skill path"),
            "--include-copilot",
            "--copilot-target-dir",
            github_dir.to_str().expect("github path"),
        ])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup should install requested resources: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["schemaVersion"], 3);
    assert_eq!(stdout["repair"]["applied"], true);
    assert!(stdout.get("headless").is_none(), "{stdout}");
    assert!(
        stdout.get("shell").is_none(),
        "shell should be skipped: {stdout}"
    );
    assert_eq!(stdout["skill"]["skipped"], false);
    assert_eq!(stdout["copilot"]["skipped"], false);
    assert!(skill_dir.join("kast/SKILL.md").is_file());
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());
    assert!(
        !home
            .join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .exists()
    );
}

#[test]
fn setup_defaults_to_shell_without_adding_headless_on_clean_machine() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&home).expect("home");

    let setup = kast(&home, &config_home)
        .args(["--output", "json", "setup", "--shell", "zsh"])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "clean setup should install local integrations without adding headless: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["repair"]["applied"], true);
    assert!(stdout.get("headless").is_none(), "{stdout}");
    assert_eq!(stdout["shell"]["shell"], "zsh");
    assert!(
        home.join(".zshrc").is_file(),
        "setup should install shell integration by default"
    );
    assert!(
        !home
            .join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .exists(),
        "plain setup must not create a headless backend"
    );
}

#[test]
fn setup_rejects_headless_inputs_when_headless_is_not_installed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let archive = write_backend_archive(temp.path(), "headless", "v9.8.7");
    std::fs::create_dir_all(&home).expect("home");

    let setup = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "setup",
            "--headless-archive",
            archive.to_str().expect("archive path"),
            "--version",
            "v9.8.7",
            "--skip-shell",
        ])
        .output()
        .expect("setup");

    assert!(
        !setup.status.success(),
        "clean setup should reject headless-specific inputs: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stderr = String::from_utf8_lossy(&setup.stderr);
    assert!(
        stderr.contains("kast install headless"),
        "error should point to the explicit install path: {stderr}"
    );
    assert!(
        !home
            .join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .exists()
    );
}

#[test]
fn setup_refreshes_existing_headless_backend() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let first_archive = write_backend_archive(&temp.path().join("first"), "headless", "v9.8.7");
    let second_archive = write_backend_archive(&temp.path().join("second"), "headless", "v9.8.8");
    std::fs::create_dir_all(&home).expect("home");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "install",
            "headless",
            "--archive",
            first_archive.to_str().expect("first archive"),
            "--version",
            "v9.8.7",
        ])
        .output()
        .expect("install headless");
    assert!(
        install.status.success(),
        "initial explicit headless install should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );

    let setup = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "setup",
            "--headless-archive",
            second_archive.to_str().expect("second archive"),
            "--version",
            "v9.8.8",
            "--force",
            "--skip-shell",
        ])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup should refresh an existing headless backend: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["headless"]["backendName"], "headless");
    assert_eq!(stdout["headless"]["version"], "v9.8.8");
    assert!(
        home.join(".kast/lib/backends/headless/current/runtime-libs/classpath.txt")
            .is_file()
    );
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("version = \"v9.8.8\""), "{config}");
}

#[test]
fn setup_skip_flags_disable_all_components() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let skill_dir = temp.path().join("skills");
    let github_dir = temp.path().join(".github");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);

    let setup = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "--output",
            "json",
            "setup",
            "--skip-repair",
            "--skip-shell",
            "--skip-headless",
            "--skip-plugin",
            "--include-skill",
            "--skip-skill",
            "--skill-target-dir",
            skill_dir.to_str().expect("skill path"),
            "--include-copilot",
            "--skip-copilot",
            "--copilot-target-dir",
            github_dir.to_str().expect("github path"),
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup with all skip flags should succeed without side effects: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert!(stdout.get("repair").is_none(), "{stdout}");
    assert!(stdout.get("shell").is_none(), "{stdout}");
    assert!(stdout.get("headless").is_none(), "{stdout}");
    assert!(stdout.get("ideaPlugin").is_none(), "{stdout}");
    assert!(stdout.get("skill").is_none(), "{stdout}");
    assert!(stdout.get("copilot").is_none(), "{stdout}");
    assert!(!config_home.join("config.toml").exists());
    assert!(!home.join(".zshrc").exists());
    assert!(!skill_dir.join("kast").exists());
    assert!(!github_dir.join("extensions/kast").exists());
    assert!(
        !jetbrains_root
            .join("IntelliJIdea2026.1/plugins/kast")
            .exists()
    );
}

#[cfg(target_os = "macos")]
#[test]
fn setup_installs_plugin_for_detected_jetbrains_profiles_on_macos() {
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

    let setup = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .args([
            "--output",
            "json",
            "setup",
            "--skip-shell",
            "--skip-headless",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("setup");

    assert!(
        setup.status.success(),
        "setup should install plugin for detected JetBrains profiles: stdout={}, stderr={}",
        String::from_utf8_lossy(&setup.stdout),
        String::from_utf8_lossy(&setup.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup json");
    assert_eq!(stdout["ideaPlugin"]["brewAction"], "install");
    assert_eq!(stdout["ideaPlugin"]["brewCommand"][1], "install");
    assert_eq!(stdout["ideaPlugin"]["brewCommand"][2], "--cask");
    assert_eq!(
        stdout["ideaPlugin"]["jetbrainsConfigRoot"],
        jetbrains_root.display().to_string()
    );
    assert_eq!(
        stdout["ideaPlugin"]["pluginDirectories"][0],
        jetbrains_root
            .join("IntelliJIdea2026.1/plugins")
            .display()
            .to_string()
    );
}

#[test]
fn up_without_installed_backend_reports_exact_backend_install_command_when_auto_start_disabled() {
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
        stderr.contains("kast install headless"),
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
        stderr.contains("kast install headless"),
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
        stderr.contains("kast install headless"),
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
        stderr.contains("kast install headless"),
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
        stderr.contains("kast install headless"),
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
        .args(["--output", "json", "install", "idea-plugin", "--dry-run"])
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
fn plugin_install_gateway_downloads_with_progress_on_stderr() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let download_dir = temp.path().join("downloads");
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
            "install",
            "plugin",
            "--download-dir",
            download_dir.to_str().expect("download dir"),
        ])
        .output()
        .expect("install plugin");

    assert!(
        install.status.success(),
        "plugin gateway should download cask: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr),
    );
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(
        stderr.contains("Fetching Kast IDEA plugin"),
        "stderr should announce the download before JSON output: {stderr}"
    );
    assert!(
        stderr.contains("Downloaded Kast IDEA plugin"),
        "stderr should confirm the downloaded artifact: {stderr}"
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "fetch");
    assert_eq!(
        stdout["downloadedPath"],
        download_dir.join("kast-plugin.zip").display().to_string()
    );
    assert!(download_dir.join("kast-plugin.zip").is_file());
}

#[test]
fn install_affected_repairs_stale_local_setup_only_when_applied() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let repo = temp.path().join("repo");
    let jetbrains_root = home.join("Library/Application Support/JetBrains");
    let profile_plugins = jetbrains_root.join("IntelliJIdea2026.1/plugins");
    let stale_backend = home.join(".kast/lib/backends/standalone-v0.7.35");
    let stale_current = home.join(".kast/lib/backends/current");
    let stale_runtime_libs = stale_current.join("runtime-libs");
    let skill = home.join(".codex/skills/kast");
    let copilot = repo.join(".github/extensions/kast");
    let shell_source = config_home.join("shell/kast.zsh");
    let old_bin = home.join(".kast/bin");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&repo).expect("repo");
    std::fs::create_dir_all(&skill).expect("skill");
    std::fs::create_dir_all(&copilot).expect("copilot");
    std::fs::create_dir_all(&old_bin).expect("old bin");
    std::fs::create_dir_all(&profile_plugins).expect("profile plugins");
    std::fs::create_dir_all(shell_source.parent().expect("shell source parent"))
        .expect("shell dir");
    std::fs::write(old_bin.join("kast"), b"old binary\n").expect("old binary");
    std::fs::write(skill.join(".kast-version"), b"old\n").expect("skill marker");
    std::fs::write(skill.join("old.txt"), b"stale\n").expect("skill stale file");
    std::fs::write(copilot.join(".kast-copilot-version"), b"old\n").expect("copilot marker");
    std::fs::write(copilot.join("old.txt"), b"stale\n").expect("copilot stale file");
    std::fs::write(
        &shell_source,
        format!(
            "# Managed by `kast install shell`; re-run that command after moving Kast.\n\
export KAST_CONFIG_HOME='{}'\n\
_kast_bin_dir='{}'\n",
            config_home.display(),
            old_bin.display()
        ),
    )
    .expect("shell source");
    #[cfg(unix)]
    std::os::unix::fs::symlink(
        "/opt/homebrew/Caskroom/kast-plugin/0.7.35/backend-idea",
        profile_plugins.join("kast"),
    )
    .expect("stale plugin symlink");
    let formula_prefix = Path::new(env!("CARGO_BIN_EXE_kast"))
        .parent()
        .expect("binary parent");
    write_fake_brew(&brew_bin, formula_prefix);
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[backends.standalone]
runtimeLibsDir = "{}"

[cli]
binaryPath = "{}"

[install]
components = ["backend:standalone"]
installedAt = "unix:1"
managedPaths = [
    "lib/backends/standalone-v0.7.35",
    "lib/backends/current",
]
platform = "macos-aarch64"
schemaVersion = 3
shellRcPatches = []
version = "0.7.35"

[[install.backends]]
installDir = "{}"
name = "standalone"
runtimeLibsDir = "{}"
version = "v0.7.35"

[[install.repos]]
copilotExtensionVersion = "old"
path = "{}"
"#,
            stale_runtime_libs.display(),
            old_bin.join("kast").display(),
            stale_backend.display(),
            stale_runtime_libs.display(),
            repo.display()
        ),
    )
    .expect("config");

    let dry_run = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_BREW_CASK_VERSION", "9.8.7")
        .args([
            "--output",
            "json",
            "install",
            "affected",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("dry run affected install");

    assert!(
        dry_run.status.success(),
        "dry run should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&dry_run.stdout),
        String::from_utf8_lossy(&dry_run.stderr)
    );
    let dry_run_stdout: serde_json::Value =
        serde_json::from_slice(&dry_run.stdout).expect("dry run json");
    assert_eq!(dry_run_stdout["applied"], false);
    assert_eq!(
        dry_run_stdout["applyCommand"],
        "kast install affected --apply"
    );
    assert!(dry_run_stdout["actions"].as_array().expect("actions").len() >= 5);
    assert!(
        std::fs::read_to_string(config_home.join("config.toml"))
            .expect("config after dry run")
            .contains("[backends.standalone]")
    );
    assert_eq!(
        std::fs::read_to_string(skill.join(".kast-version")).expect("skill after dry run"),
        "old\n"
    );

    let apply = kast(&home, &config_home)
        .env("PATH", &brew_bin)
        .env("KAST_FAKE_BREW_CASK_VERSION", "9.8.7")
        .args([
            "--output",
            "json",
            "install",
            "affected",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
            "--apply",
        ])
        .output()
        .expect("apply affected install");

    assert!(
        apply.status.success(),
        "apply should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr)
    );
    let apply_stdout: serde_json::Value =
        serde_json::from_slice(&apply.stdout).expect("apply json");
    assert_eq!(apply_stdout["applied"], true);
    assert!(
        !apply_stdout["backups"]
            .as_array()
            .expect("backups")
            .is_empty(),
        "apply should create backups"
    );
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after apply");
    assert!(!config_after.contains("[backends.standalone]"));
    assert!(!config_after.contains("backend:standalone"));
    assert!(config_after.contains(env!("CARGO_BIN_EXE_kast")));
    assert_ne!(
        std::fs::read_to_string(skill.join(".kast-version")).expect("skill after apply"),
        "old\n"
    );
    assert!(!skill.join("old.txt").exists());
    assert_ne!(
        std::fs::read_to_string(copilot.join(".kast-copilot-version"))
            .expect("copilot after apply"),
        "old\n"
    );
    assert!(!copilot.join("old.txt").exists());
    let shell_after = std::fs::read_to_string(&shell_source).expect("shell after apply");
    assert!(!shell_after.contains(&old_bin.display().to_string()));
    #[cfg(unix)]
    assert_eq!(
        std::fs::read_link(profile_plugins.join("kast")).expect("plugin symlink after apply"),
        Path::new("/opt/homebrew/Caskroom/kast-plugin/9.8.7/backend-idea")
    );
}

#[test]
fn install_affected_repairs_stale_brew_and_removed_backend_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let stale_bin = home.join(".kast/bin");
    let stale_backend = home.join(".kast/lib/backends/standalone-v0.7.35");
    let stale_current = home.join(".kast/lib/backends/current");
    let stale_runtime_libs = stale_current.join("runtime-libs");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::write(stale_bin.join("kast"), b"old binary\n").expect("old binary");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[backends.standalone]
runtimeLibsDir = "{}"

[cli]
binaryPath = "{}"

[install]
components = ["backend:standalone"]
installedAt = "unix:1"
managedPaths = [
    "lib/backends/standalone-v0.7.35",
    "lib/backends/current",
]
platform = "macos-aarch64"
schemaVersion = 3
shellRcPatches = []
version = "0.7.35"

[[install.backends]]
installDir = "{}"
name = "standalone"
runtimeLibsDir = "{}"
version = "v0.7.35"
"#,
            stale_runtime_libs.display(),
            stale_bin.join("kast").display(),
            stale_backend.display(),
            stale_runtime_libs.display(),
        ),
    )
    .expect("config");

    let repair = kast(&home, &config_home)
        .args(["--output", "json", "install", "affected", "--apply"])
        .output()
        .expect("install affected");

    assert!(
        repair.status.success(),
        "install affected should repair stale state: stdout={}, stderr={}",
        String::from_utf8_lossy(&repair.stdout),
        String::from_utf8_lossy(&repair.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&repair.stdout).expect("repair json");
    assert_eq!(stdout["applied"], true);
    assert!(
        stdout["actions"]
            .as_array()
            .expect("actions")
            .iter()
            .any(|action| action["kind"] == "update-cli-binary-path"),
        "install affected should update stale cli binary path: {stdout}"
    );
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after repair");
    assert!(!config_after.contains("[backends.standalone]"));
    assert!(!config_after.contains("backend:standalone"));
    assert!(config_after.contains(env!("CARGO_BIN_EXE_kast")));
}

#[test]
fn ordinary_commands_repair_stale_install_version_once() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let managed_bin = install_root.join("bin/kast");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(managed_bin.parent().expect("managed bin parent"))
        .expect("managed bin parent");
    std::fs::write(&managed_bin, b"managed kast\n").expect("managed bin");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[paths]
installRoot = "{}"

[install]
components = ["shell"]
installedAt = "unix:1"
managedPaths = ["bin/kast"]
platform = "macos-aarch64"
schemaVersion = 3
shellRcPatches = []
version = "0.0.1"
"#,
            install_root.display()
        ),
    )
    .expect("config");

    let first = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("first doctor");

    assert!(
        first.status.success(),
        "first doctor should repair stale install metadata: stdout={}, stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr)
    );
    let first_stdout: serde_json::Value =
        serde_json::from_slice(&first.stdout).expect("first doctor json");
    assert_eq!(
        first_stdout["install"]["version"],
        env!("CARGO_PKG_VERSION")
    );
    let config_after_first =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after first");
    assert!(
        config_after_first.contains(&format!("version = \"{}\"", env!("CARGO_PKG_VERSION"))),
        "{config_after_first}"
    );

    let second = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("second doctor");

    assert!(
        second.status.success(),
        "second doctor should be idempotent: stdout={}, stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr)
    );
    let config_after_second =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after second");
    assert_eq!(
        config_after_second, config_after_first,
        "repair should not rewrite install metadata when the version already matches"
    );
}

#[test]
fn install_resource_gateways_support_force_and_current_versions() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let skill_dir = temp.path().join("skills");
    let github_dir = temp.path().join(".github");
    let stale_skill = skill_dir.join("kast");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&stale_skill).expect("stale skill");
    std::fs::write(stale_skill.join(".kast-version"), b"old\n").expect("stale marker");
    std::fs::write(stale_skill.join("old.txt"), b"old\n").expect("stale file");

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
    assert!(!stale_skill.join("old.txt").exists());

    std::fs::write(stale_skill.join("force-removes.txt"), b"stale\n").expect("force marker");
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
    assert!(!stale_skill.join("force-removes.txt").exists());

    let skill_marker =
        std::fs::read_to_string(stale_skill.join(".kast-version")).expect("skill marker");
    assert_eq!(skill_marker.trim(), skill_stdout["version"]);

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
    assert!(github_dir.join("extensions/kast/extension.mjs").is_file());

    let copilot_marker =
        std::fs::read_to_string(github_dir.join("extensions/kast/.kast-copilot-version"))
            .expect("copilot marker");
    assert_eq!(copilot_marker.trim(), copilot_stdout["version"]);
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
            "--output",
            "json",
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
fn plugin_install_repairs_stale_config_before_linking_profiles() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let brew_bin = temp.path().join("bin");
    let jetbrains_root = temp.path().join("jetbrains");
    let stale_bin = home.join(".kast/bin");
    let stale_backend = home.join(".kast/lib/backends/standalone-v0.7.35");
    let stale_runtime_libs = home.join(".kast/lib/backends/current/runtime-libs");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&stale_bin).expect("stale bin");
    std::fs::create_dir_all(jetbrains_root.join("IntelliJIdea2026.1")).expect("profile");
    std::fs::write(stale_bin.join("kast"), b"old binary\n").expect("old binary");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            r#"[backends.standalone]
runtimeLibsDir = "{}"

[cli]
binaryPath = "{}"

[install]
components = ["backend:standalone"]
installedAt = "unix:1"
managedPaths = ["lib/backends/standalone-v0.7.35", "lib/backends/current"]
platform = "macos-aarch64"
schemaVersion = 3
version = "0.7.35"

[[install.backends]]
installDir = "{}"
name = "standalone"
runtimeLibsDir = "{}"
version = "v0.7.35"
"#,
            stale_runtime_libs.display(),
            stale_bin.join("kast").display(),
            stale_backend.display(),
            stale_runtime_libs.display(),
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
            "--link-jetbrains-profiles",
            "--jetbrains-config-root",
            jetbrains_root.to_str().expect("jetbrains root"),
        ])
        .output()
        .expect("install plugin");

    assert!(
        install.status.success(),
        "plugin install should repair config before linking profiles: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&install.stdout).expect("install json");
    assert_eq!(stdout["brewAction"], "install");
    let config_after =
        std::fs::read_to_string(config_home.join("config.toml")).expect("config after repair");
    assert!(!config_after.contains("[backends.standalone]"));
    assert!(!config_after.contains("backend:standalone"));
    assert!(config_after.contains(env!("CARGO_BIN_EXE_kast")));
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

[cli]
binaryPath = "{}"

[install]
version = "0.1.0"
components = []
managedPaths = ["backends"]
schemaVersion = 3
"#,
            install_root.display(),
            env!("CARGO_BIN_EXE_kast")
        ),
    )
    .expect("config");

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
fn archive_install_writes_config_owned_install_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let archive = temp.path().join("kast.zip");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::write(&archive, b"portable archive placeholder").expect("archive");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
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

    let doctor = kast(&home, &config_home)
        .args(["--output", "json", "doctor"])
        .output()
        .expect("doctor");
    assert!(
        doctor.status.success(),
        "doctor should accept config-owned install state: stdout={}, stderr={}",
        String::from_utf8_lossy(&doctor.stdout),
        String::from_utf8_lossy(&doctor.stderr)
    );
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
    assert!(quickstart.contains("command -v kast"));
    assert!(quickstart.contains("kast rpc"));
    assert!(quickstart.contains("kast metrics impact"));
    assert!(quickstart.contains("kast demo"));
    assert!(routing_reference.contains("rust-kast-cli"));
    assert!(!routing_reference.contains("evals/"));
    assert!(routing_builder.contains("\"expected_route\": \"rust-kast-cli\""));
    assert!(routing_builder.contains("kast demo --view symbol --json"));
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

#[test]
fn repo_local_copilot_extension_is_installed_for_extension_only_use() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");
    let extension_root = root.join(".github/extensions/kast");

    assert!(
        extension_root.join("extension.mjs").is_file(),
        "repo-local Copilot extension entrypoint must exist for extension-only GitHub Copilot use"
    );
    assert!(
        extension_root.join("_shared/kast-tools.mjs").is_file(),
        "repo-local Copilot extension must include its own tool loader"
    );
    assert!(
        extension_root.join("_shared/commands.json").is_file(),
        "repo-local Copilot extension must include its own command catalog"
    );
    assert!(
        !root
            .join(".github/extensions/_shared/commands.json")
            .exists(),
        "repo-local extension must not depend on the legacy shared catalog location"
    );
}
