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
    let control_cli = staging.join("kastctl");
    let agent_cli = staging.join("kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &control_cli)
        .expect("copy test kastctl binary");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &agent_cli).expect("copy test kast binary");
    set_executable_for_test(&control_cli);
    set_executable_for_test(&agent_cli);
    let status = Command::new("zip")
        .args([
            "-qr",
            archive.to_str().expect("archive path"),
            "kastctl",
            "kast",
        ])
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
    std::fs::create_dir_all(bundle.join("libexec")).expect("bundle libexec");
    std::fs::create_dir_all(bundle.join("plugins")).expect("bundle plugins");
    std::fs::create_dir_all(backend_dir.join("runtime-libs")).expect("runtime libs");
    std::fs::create_dir_all(backend_dir.join("idea-home/lib")).expect("idea lib");
    std::fs::create_dir_all(backend_dir.join("idea-home/modules")).expect("idea modules");
    std::fs::create_dir_all(backend_dir.join("idea-home/plugins/kast-headless"))
        .expect("kast-headless plugin");

    let bundled_control = bundle.join("libexec/kastctl");
    let bundled_kast = bundle.join("bin/kast");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &bundled_control)
        .expect("copy test kastctl binary");
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
    set_executable_for_test(&bundled_control);
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
                "cli": {"path": "libexec/kastctl"},
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
                    "path": "libexec/kastctl",
                    "sha256": test_path_sha256(&bundled_control)
                },
                {
                    "role": "agent-cli",
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
