#![allow(dead_code, unused_imports)]

pub(crate) mod metrics;

pub(crate) use std::path::Path;
pub(crate) use std::path::PathBuf;
pub(crate) use std::process::Command;
pub(crate) use std::{io::BufRead, io::BufReader, io::Write, os::unix::net::UnixListener, thread};

pub(crate) fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

pub(crate) fn default_install_root(home: &Path) -> PathBuf {
    home.join(".local/share/kast")
}

pub(crate) fn default_descriptor_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("runtime/daemons")
}

pub(crate) fn default_bin_dir(home: &Path) -> PathBuf {
    home.join(".local/bin")
}

pub(crate) fn install_manifest_path(home: &Path) -> PathBuf {
    default_install_root(home).join("install.json")
}

pub(crate) fn write_macos_homebrew_receipt_for_test(home: &Path, cli_binary: &Path) -> PathBuf {
    let receipt = home.join("Library/Application Support/Kast/homebrew-install.json");
    std::fs::create_dir_all(receipt.parent().expect("receipt parent")).expect("receipt dir");
    std::fs::write(
        &receipt,
        serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 1,
            "authority": "macos-homebrew",
            "cli": {
                "binary": cli_binary.display().to_string(),
                "formulaPrefix": cli_binary.parent().expect("formula prefix").display().to_string(),
                "version": env!("CARGO_PKG_VERSION")
            },
            "plugin": {
                "caskToken": "amichne/kast/kast-plugin",
                "version": env!("CARGO_PKG_VERSION")
            },
            "updatedAt": "unix:1"
        }))
        .expect("receipt json"),
    )
    .expect("receipt");
    receipt
}

pub(crate) fn write_legacy_local_install_for_test(home: &Path, config_home: &Path) -> PathBuf {
    let install_root = default_install_root(home);
    let shim = default_bin_dir(home).join("kast");
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
        install_manifest_path(home),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "legacy-test-install",
            "profile": "user-local",
            "activeVersion": "0.12.3",
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(home).display().to_string(),
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
    #[cfg(target_os = "macos")]
    {
        let workspace: PathBuf = workspace.components().collect();
        let skill = workspace.join(".agents/skills/kast/SKILL.md");
        std::fs::create_dir_all(skill.parent().expect("skill parent")).expect("skill dir");
        std::fs::write(&skill, "# Kast\n").expect("skill");
        let metadata = workspace.join(".kast/setup/workspace.json");
        std::fs::create_dir_all(metadata.parent().expect("metadata parent")).expect("metadata dir");
        std::fs::write(
            metadata,
            serde_json::to_string_pretty(&serde_json::json!({
                "schemaVersion": 1,
                "preparedBy": "kast-intellij-plugin",
                "pluginVersion": env!("CARGO_PKG_VERSION"),
                "cliVersion": env!("CARGO_PKG_VERSION"),
                "workspaceRoot": workspace.display().to_string(),
                "cliBinary": env!("CARGO_BIN_EXE_kast"),
                "backend": "idea",
                "socketPath": default_socket_path_for_test(&workspace).display().to_string(),
                "requiredArtifacts": [
                    ".agents/skills/kast/SKILL.md",
                    ".kast/setup/workspace.json"
                ]
            }))
            .expect("metadata json"),
        )
        .expect("metadata");
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = workspace;
    }
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

pub(crate) fn write_fake_brew(bin_dir: &Path, formula_prefix: &Path) -> PathBuf {
    let brew = bin_dir.join("brew");
    let ps = bin_dir.join("ps");
    std::fs::create_dir_all(bin_dir).expect("brew bin");
    std::fs::write(
        &brew,
        format!(
            r#"#!/bin/sh
set -eu
state_file="${{HOME:-/tmp}}/.fake-brew-kast-plugin-version"
plugin_version="${{KAST_FAKE_BREW_PLUGIN_VERSION:-{}}}"
if [ "$1" = "--prefix" ] && [ "$#" -eq 1 ]; then
  printf '%s\n' "/opt/homebrew"
elif [ "$1" = "--prefix" ] && [ "$2" = "kast" ]; then
  printf '%s\n' "{}"
elif [ "$1" = "info" ] && [ "$2" = "--json=v2" ] && [ "$3" = "kast" ]; then
  printf '%s\n' '{{"formulae":[{{"name":"kast","tap":"amichne/kast"}}],"casks":[]}}'
elif [ "$1" = "info" ] && [ "$2" = "--json=v2" ] && [ "$3" = "--cask" ]; then
  printf '%s\n' "{{\"formulae\":[],\"casks\":[{{\"token\":\"kast-plugin\",\"full_token\":\"amichne/kast/kast-plugin\",\"version\":\"${{plugin_version}}\"}}]}}"
elif [ "$1" = "fetch" ] && [ "$2" = "--cask" ]; then
  cache="${{HOME:-/tmp}}/000--kast-plugin.zip"
  printf 'fake plugin zip\n' > "$cache"
  printf 'fake brew fetched kast plugin\n' >&2
elif [ "$1" = "--cache" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{HOME:-/tmp}}/000--kast-plugin.zip"
elif [ "$1" = "install" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{KAST_FAKE_BREW_INSTALL_VERSION:-${{plugin_version}}}}" > "$state_file"
  printf 'fake brew installed kast plugin\n' >&2
elif [ "$1" = "reinstall" ] && [ "$2" = "--cask" ]; then
  printf '%s\n' "${{KAST_FAKE_BREW_INSTALL_VERSION:-${{plugin_version}}}}" > "$state_file"
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
            env!("CARGO_PKG_VERSION"),
            formula_prefix.display()
        ),
    )
    .expect("brew script");
    std::fs::write(
        &ps,
        r#"#!/bin/sh
set -eu
case "${KAST_FAKE_PS_JETBRAINS:-}" in
  "IntelliJ IDEA") printf '%s\n' '/Applications/IntelliJ IDEA.app/Contents/MacOS/idea /workspace' ;;
  "Android Studio") printf '%s\n' '/Applications/Android Studio.app/Contents/MacOS/studio /workspace' ;;
  *) printf '%s\n' 'COMMAND' ;;
esac
"#,
    )
    .expect("ps script");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for executable in [&brew, &ps] {
            let mut permissions = std::fs::metadata(executable)
                .expect("fake executable metadata")
                .permissions();
            permissions.set_mode(0o755);
            std::fs::set_permissions(executable, permissions).expect("fake executable mode");
        }
    }
    brew
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
