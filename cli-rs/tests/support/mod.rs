#![allow(dead_code, unused_imports)]

pub(crate) mod metrics;
pub(crate) mod workspace_files;

pub(crate) use std::path::Path;
pub(crate) use std::path::PathBuf;
pub(crate) use std::process::Command;
pub(crate) use std::{io::BufRead, io::BufReader, io::Write, os::unix::net::UnixListener, thread};

pub(crate) fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    kast_at(Path::new(env!("CARGO_BIN_EXE_kast")), home, config_home)
}

pub(crate) fn kast_at(binary: &Path, home: &Path, config_home: &Path) -> Command {
    let mut command = Command::new(binary);
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

pub(crate) fn default_install_root(home: &Path) -> PathBuf {
    home.join(".local/share/kast")
}

pub(crate) fn default_descriptor_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("state/runtime/daemons")
}

pub(crate) fn default_bin_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("current/bin")
}

pub(crate) fn install_manifest_path(home: &Path) -> PathBuf {
    default_install_root(home).join("current/receipt.json")
}

pub(crate) fn write_current_cli_install_manifest_for_test(home: &Path, _config_home: &Path) {
    let install_root = default_install_root(home);
    let binary = default_bin_dir(home).join("kast");
    let config_root = install_root.join("current/config");
    std::fs::create_dir_all(default_bin_dir(home)).expect("bin directory");
    std::fs::create_dir_all(&install_root).expect("install root");
    std::fs::create_dir_all(&config_root).expect("config root");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &binary).expect("active Kast binary");
    std::fs::write(
        install_manifest_path(home),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "current-cli-test-install",
            "releaseDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "manifestDigest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "profile": "user-local",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(home).display().to_string(),
                "config": config_root.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": install_root.join("state/cache").display().to_string(),
                "runtime": install_root.join("state/runtime").display().to_string(),
                "logs": install_root.join("state/logs").display().to_string(),
                "locks": install_root.display().to_string()
            },
            "entrypoints": {
                "shim": binary.display().to_string(),
                "activeBinary": binary.display().to_string()
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["cli"],
            "schemaVersion": 3
        }))
        .expect("install manifest JSON"),
    )
    .expect("install manifest");
}

pub(crate) fn write_active_kast_for_test(home: &Path, config_home: &Path) -> PathBuf {
    write_current_cli_install_manifest_for_test(home, config_home);
    default_bin_dir(home).join("kast")
}

pub(crate) fn write_legacy_local_install_for_test(home: &Path, config_home: &Path) -> PathBuf {
    let install_root = default_install_root(home);
    let shim = home.join(".local/bin/kast");
    let active_binary = install_root.join("versions/0.12.3/bin/kast");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary dir");
    std::fs::create_dir_all(shim.parent().expect("shim parent")).expect("shim dir");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &active_binary).expect("active binary");
    std::fs::write(
        &shim,
        format!(
            "#!/usr/bin/env bash\nset -euo pipefail\nexec '{}' \"$@\"\n",
            active_binary.display()
        ),
    )
    .expect("shim");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&shim, std::fs::Permissions::from_mode(0o755)).expect("shim mode");
    }
    std::fs::create_dir_all(&install_root).expect("install root");
    std::fs::write(
        install_root.join("install.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "legacy-test-install",
            "profile": "user-local",
            "activeVersion": "0.12.3",
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": home.join(".local/bin").display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": shim.display().to_string(),
                "activeBinary": active_binary.display().to_string()
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": "0.12.3",
            "components": ["cli", "config"],
            "ownedPaths": [shim.display().to_string()],
            "schemaVersion": 3
        }))
        .expect("legacy manifest json"),
    )
    .expect("legacy manifest");
    shim
}

pub(crate) fn write_macos_plugin_workspace_metadata(workspace: &Path) {
    write_macos_plugin_workspace_metadata_for_cli(
        workspace,
        Path::new(env!("CARGO_BIN_EXE_kast")),
        env!("CARGO_PKG_VERSION"),
    );
}

pub(crate) fn write_macos_plugin_workspace_metadata_for_cli(
    workspace: &Path,
    cli_binary: &Path,
    cli_version: &str,
) {
    #[cfg(target_os = "macos")]
    {
        let workspace: PathBuf = workspace.components().collect();
        let metadata = workspace.join(".kast/setup/workspace.json");
        std::fs::create_dir_all(metadata.parent().expect("metadata parent")).expect("metadata dir");
        std::fs::write(
            metadata,
            serde_json::to_string_pretty(&serde_json::json!({
                "schemaVersion": 3,
                "preparedBy": "kast-intellij-plugin",
                "workspaceRoot": workspace.display().to_string(),
                "cliBinary": cli_binary.display().to_string(),
                "backend": "idea",
                "socketPath": default_socket_path_for_test(&workspace).display().to_string(),
                "compatibility": {
                    "pluginVersion": cli_version,
                    "cliVersion": cli_version,
                    "protocolRevision": 1,
                    "workspaceMetadataRevision": 3,
                    "readCapabilities": [
                        "RESOLVE_SYMBOL",
                        "FIND_REFERENCES",
                        "CALL_HIERARCHY",
                        "TYPE_HIERARCHY",
                        "SEMANTIC_INSERTION_POINT",
                        "DIAGNOSTICS",
                        "FILE_OUTLINE",
                        "WORKSPACE_SYMBOL_SEARCH",
                        "WORKSPACE_SEARCH",
                        "WORKSPACE_FILES",
                        "IMPLEMENTATIONS",
                        "CODE_ACTIONS",
                        "COMPLETIONS"
                    ],
                    "mutationCapabilities": [
                        "RENAME",
                        "APPLY_EDITS",
                        "FILE_OPERATIONS",
                        "OPTIMIZE_IMPORTS",
                        "REFRESH_WORKSPACE"
                    ],
                    "runtimeIdentity": {
                        "implementationVersion": cli_version,
                        "backendKind": "IDEA"
                    }
                },
                "requiredArtifacts": [
                    ".kast/setup/workspace.json"
                ]
            }))
            .expect("metadata json"),
        )
        .expect("metadata");
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (workspace, cli_binary, cli_version);
    }
}

pub(crate) fn spawn_scripted_idea_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    write_macos_plugin_workspace_metadata(workspace);
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "idea",
        1,
        scripted_results,
    )
}

pub(crate) struct ScriptedCliAuthority<'a> {
    binary: &'a Path,
    version: &'a str,
}

impl<'a> ScriptedCliAuthority<'a> {
    pub(crate) fn new(binary: &'a Path, version: &'a str) -> Self {
        assert!(binary.is_file(), "scripted CLI authority binary");
        assert!(!version.trim().is_empty(), "scripted CLI authority version");
        Self { binary, version }
    }
}

pub(crate) fn spawn_scripted_idea_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    cli_authority: ScriptedCliAuthority<'_>,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    write_macos_plugin_workspace_metadata_for_cli(
        workspace,
        cli_authority.binary,
        cli_authority.version,
    );
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "idea",
        invocation_count,
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_headless_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "headless",
        1,
        scripted_results,
    )
}

fn spawn_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        config_home.join("config.toml"),
        format!("[runtime]\ndefaultBackend = \"{backend_name}\"\n"),
    )
    .expect("config");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": backend_name,
            "backendVersion": "scripted-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 3
        }]))
        .expect("descriptor json"),
    )
    .expect("descriptor");

    let listener = UnixListener::bind(socket_path).expect("bind scripted backend");
    listener
        .set_nonblocking(true)
        .expect("nonblocking scripted backend");
    let server_workspace = workspace.to_path_buf();
    let server_backend_name = backend_name.to_string();
    thread::spawn(move || {
        let mut requests = Vec::new();
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests = 2 * invocation_count + scripted_results.len();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        while (requests.len() < expected_requests || scripted_results.len() > 0)
            && std::time::Instant::now() < deadline
        {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(std::time::Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept scripted backend client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking scripted backend stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"].as_str().expect("method");
            let result = match method {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => serde_json::json!({
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [
                        "symbol/resolve",
                        "symbol/references",
                        "symbol/callers",
                        "symbol/implementations",
                        "symbol/hierarchy",
                        "raw/call-hierarchy",
                        "raw/implementations",
                        "raw/type-hierarchy"
                    ],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                _ => {
                    let (expected_method, result) = scripted_results
                        .next()
                        .unwrap_or_else(|| panic!("unexpected scripted method: {method}"));
                    assert_eq!(method, expected_method, "scripted method order");
                    result
                }
            };
            requests.push(request);
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write scripted response");
        }
        requests
    })
}

pub(crate) fn spawn_sequenced_idea_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    responses: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    write_macos_plugin_workspace_metadata(workspace);
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("config");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": "idea",
            "backendVersion": "scripted-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 3
        }]))
        .expect("descriptor json"),
    )
    .expect("descriptor");

    let listener = UnixListener::bind(socket_path).expect("bind sequenced backend");
    thread::spawn(move || {
        let mut requests = Vec::with_capacity(responses.len());
        for (expected_method, result) in responses {
            let (mut stream, _) = listener.accept().expect("accept sequenced client");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            assert_eq!(request["method"], expected_method, "scripted method order");
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write sequenced response");
            requests.push(request);
        }
        requests
    })
}

#[cfg(target_os = "macos")]
fn default_socket_path_for_test(workspace: &Path) -> PathBuf {
    use sha2::{Digest, Sha256};

    let normalized: PathBuf = workspace.components().collect();
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    std::env::temp_dir().join(format!("kast-{}.sock", &hex::encode(digest)[0..12]))
}

pub(crate) fn path_report_entry<'a>(
    report: &'a serde_json::Value,
    key: &str,
) -> &'a serde_json::Value {
    report["entries"]
        .as_array()
        .expect("path report entries")
        .iter()
        .find(|entry| entry["key"] == key)
        .unwrap_or_else(|| panic!("missing path report entry {key}: {report:#?}"))
}

pub(crate) fn write_backend_archive(root: &Path, backend: &str, version: &str) -> PathBuf {
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

pub(crate) fn write_cli_archive(root: &Path) -> PathBuf {
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

pub(crate) fn write_install_bundle_source(root: &Path, version: &str) -> PathBuf {
    let platform = "ubuntu-debian-headless-x86_64";
    let bundle = root.join(format!("kast-{platform}-{version}"));
    let backend_dir = bundle.join(format!("lib/backends/headless-{version}"));
    std::fs::create_dir_all(bundle.join("bin")).expect("bundle bin");
    std::fs::create_dir_all(bundle.join("plugins")).expect("bundle plugins");
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
    std::fs::write(bundle.join("install.sh"), "#!/usr/bin/env bash\n").expect("bootstrap script");
    std::fs::write(bundle.join("plugins/kast.zip"), b"plugin").expect("plugin");
    set_executable_for_test(&bundled_kast);
    set_executable_for_test(&backend_dir.join("kast-headless"));
    set_executable_for_test(&bundle.join("install.sh"));

    let normalized_version = version.trim_start_matches('v');
    std::fs::write(
        bundle.join("manifest.json"),
        serde_json::to_string_pretty(&serde_json::json!({
            "schemaVersion": 3,
            "kind": "KAST_INSTALL_BUNDLE",
            "profile": "ubuntu-debian-headless",
            "version": version,
            "platform": platform,
            "entrypoint": "install.sh",
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
                    "sha256": test_path_sha256(&bundled_kast)
                },
                {
                    "role": "headless-backend",
                    "path": format!("lib/backends/headless-{version}"),
                    "sha256": test_path_sha256(&backend_dir)
                },
                {
                    "role": "plugin",
                    "path": "plugins/kast.zip",
                    "sha256": test_path_sha256(&bundle.join("plugins/kast.zip"))
                }
            ]
        }))
        .expect("bundle manifest"),
    )
    .expect("write manifest");
    bundle
}

pub(crate) fn test_path_sha256(path: &Path) -> String {
    use sha2::{Digest, Sha256};

    if path.is_file() {
        return hex::encode(Sha256::digest(std::fs::read(path).expect("artifact bytes")));
    }
    let mut files = Vec::new();
    fn collect(root: &Path, directory: &Path, files: &mut Vec<PathBuf>) {
        for entry in std::fs::read_dir(directory).expect("artifact directory") {
            let entry = entry.expect("artifact entry");
            if entry.path().is_dir() {
                collect(root, &entry.path(), files);
            } else {
                files.push(
                    entry
                        .path()
                        .strip_prefix(root)
                        .expect("relative artifact")
                        .to_path_buf(),
                );
            }
        }
    }
    collect(path, path, &mut files);
    files.sort();
    let mut digest = Sha256::new();
    for relative in files {
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update(b"\n");
        digest.update(test_path_sha256(&path.join(&relative)).as_bytes());
        digest.update(b"\n");
    }
    hex::encode(digest.finalize())
}

pub(crate) fn write_bundle_tarball(root: &Path, bundle: &Path) -> PathBuf {
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

pub(crate) fn write_malicious_bundle_tarball(root: &Path) -> PathBuf {
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
pub(crate) fn set_executable_for_test(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = std::fs::metadata(path).expect("metadata").permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(path, permissions).expect("mode");
}

#[cfg(not(unix))]
pub(crate) fn set_executable_for_test(_path: &Path) {}
