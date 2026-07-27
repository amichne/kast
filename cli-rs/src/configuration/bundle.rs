use serde::{Deserialize, Serialize};

pub(crate) const BUNDLE_MANIFEST_FILE: &str = "manifest.json";
pub(crate) const BUNDLE_MANIFEST_SCHEMA_VERSION: u32 = 3;
pub(crate) const BUNDLE_MANIFEST_KIND: &str = "KAST_INSTALL_BUNDLE";
pub(crate) const UBUNTU_DEBIAN_HEADLESS_PLATFORM_ID: &str = "ubuntu-debian-headless-x86_64";
pub(crate) const UBUNTU_DEBIAN_HEADLESS_PROFILE: &str = "ubuntu-debian-headless";
pub(crate) const UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT: &str = "install.sh";
pub(crate) const HEADLESS_BACKEND_KIND: &str = "headless";
pub(crate) const HEADLESS_BACKEND_NAME: &str = "headless";
pub(crate) const HEADLESS_BACKEND_ROLE: &str = "headless-backend";
pub(crate) const HEADLESS_BACKEND_ARCHIVE_ROOT: &str = "backend-headless";
pub(crate) const HEADLESS_BACKEND_LAUNCHER: &str = "kast-headless";
pub(crate) const HEADLESS_REQUIRED_JAVA_OPT: &str = "-Didea.force.use.core.classloader=true";

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct BundleVersion(String);

impl BundleVersion {
    pub(crate) fn parse(value: &str) -> std::result::Result<Self, String> {
        let value = value.trim();
        if value.is_empty() {
            return Err("must not be empty".to_string());
        }
        if matches!(value, "." | "..") {
            return Err("must be a version label, not a relative path component".to_string());
        }
        if !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'+'))
        {
            return Err(
                "must contain only ASCII letters, digits, '.', '_', '-', or '+'".to_string(),
            );
        }
        Ok(Self(value.to_string()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }

    pub(crate) fn normalized(&self) -> String {
        normalize_version(&self.0)
    }

    pub(crate) fn into_string(self) -> String {
        self.0
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleManifest {
    pub(crate) schema_version: u32,
    pub(crate) kind: String,
    pub(crate) profile: String,
    pub(crate) version: String,
    pub(crate) platform: String,
    pub(crate) entrypoint: String,
    pub(crate) java_requirement: String,
    pub(crate) build_commit: String,
    pub(crate) activation: BundleActivation,
    #[serde(default)]
    pub(crate) artifacts: Vec<BundleArtifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleActivation {
    pub(crate) cli: BundleCliActivation,
    pub(crate) backend: BundleBackendActivation,
    pub(crate) shim: BundleShimActivation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleCliActivation {
    pub(crate) path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleBackendActivation {
    pub(crate) kind: String,
    pub(crate) name: String,
    pub(crate) version: String,
    pub(crate) install_dir: String,
    pub(crate) launcher: String,
    pub(crate) runtime_libs_dir: String,
    pub(crate) idea_home: String,
    pub(crate) required_plugin: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleShimActivation {
    #[serde(default)]
    pub(crate) java_opts: Vec<String>,
    #[serde(default)]
    pub(crate) exports_install_root: bool,
    #[serde(default)]
    pub(crate) exports_config_home: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BundleArtifact {
    pub(crate) role: String,
    pub(crate) path: String,
    pub(crate) sha256: String,
}

pub(crate) fn ubuntu_debian_headless_manifest(
    version: &str,
    platform: &str,
    artifact_sha256: [String; 3],
    build_commit: String,
) -> BundleManifest {
    let [cli_sha256, backend_sha256, plugin_sha256] = artifact_sha256;
    let backend_install_name = format!("headless-{version}");
    BundleManifest {
        schema_version: BUNDLE_MANIFEST_SCHEMA_VERSION,
        kind: BUNDLE_MANIFEST_KIND.to_string(),
        profile: UBUNTU_DEBIAN_HEADLESS_PROFILE.to_string(),
        version: version.to_string(),
        platform: platform.to_string(),
        entrypoint: UBUNTU_DEBIAN_HEADLESS_ENTRYPOINT.to_string(),
        java_requirement: "Java 21 or newer available on PATH, or KAST_JAVA_CMD set".to_string(),
        build_commit,
        activation: BundleActivation {
            cli: BundleCliActivation {
                path: "bin/kast".to_string(),
            },
            backend: BundleBackendActivation {
                kind: HEADLESS_BACKEND_KIND.to_string(),
                name: HEADLESS_BACKEND_NAME.to_string(),
                version: normalize_version(version),
                install_dir: format!("lib/backends/{backend_install_name}"),
                launcher: HEADLESS_BACKEND_LAUNCHER.to_string(),
                runtime_libs_dir: "runtime-libs".to_string(),
                idea_home: "idea-home".to_string(),
                required_plugin: "idea-home/plugins/kast-headless".to_string(),
            },
            shim: BundleShimActivation {
                java_opts: vec![HEADLESS_REQUIRED_JAVA_OPT.to_string()],
                exports_install_root: true,
                exports_config_home: true,
            },
        },
        artifacts: vec![
            BundleArtifact {
                role: "cli".to_string(),
                path: "bin/kast".to_string(),
                sha256: cli_sha256,
            },
            BundleArtifact {
                role: HEADLESS_BACKEND_ROLE.to_string(),
                path: format!("lib/backends/{backend_install_name}"),
                sha256: backend_sha256,
            },
            BundleArtifact {
                role: "plugin".to_string(),
                path: "plugins/kast.zip".to_string(),
                sha256: plugin_sha256,
            },
        ],
    }
}

pub(crate) fn normalize_version(value: &str) -> String {
    value.trim().trim_start_matches('v').to_string()
}
