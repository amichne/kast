use std::env;
use std::fmt;
use std::ops::Deref;
use std::str::FromStr;

const ALLOW_TEST_HOST_EVIDENCE: &str = "KAST_TEST_ALLOW_PACKAGE_HOST_EVIDENCE";
const TEST_HOST_OS: &str = "KAST_TEST_PACKAGE_HOST_OS";
const TEST_HOST_ARCH: &str = "KAST_TEST_PACKAGE_HOST_ARCH";
const UNSUPPORTED_NATIVE_HOST_DEFAULT: &str = "__kast_unsupported_native_package_host";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SetupBundlePlatform {
    MacosArm64,
    MacosX64,
    LinuxX64,
}

impl SetupBundlePlatform {
    fn as_str(self) -> &'static str {
        match self {
            Self::MacosArm64 => "macos-arm64",
            Self::MacosX64 => "macos-x64",
            Self::LinuxX64 => "linux-x64",
        }
    }

    /// Canonical platform values contain no surrounding whitespace, so this
    /// identity transition retains the admitted platform proof for packaging.
    pub(crate) fn trim(&self) -> &Self {
        self
    }
}

impl Deref for SetupBundlePlatform {
    type Target = str;

    fn deref(&self) -> &Self::Target {
        self.as_str()
    }
}

impl fmt::Display for SetupBundlePlatform {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl FromStr for SetupBundlePlatform {
    type Err = SetupBundlePlatformError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "macos-arm64" => Ok(Self::MacosArm64),
            "macos-x64" => Ok(Self::MacosX64),
            "linux-x64" => Ok(Self::LinuxX64),
            UNSUPPORTED_NATIVE_HOST_DEFAULT => native_platform().and_then(|_| {
                Err(SetupBundlePlatformError::UnsupportedTarget {
                    observed: value.to_string(),
                })
            }),
            _ => Err(SetupBundlePlatformError::UnsupportedTarget {
                observed: value.to_string(),
            }),
        }
    }
}

pub(super) fn native_default_value() -> &'static str {
    native_platform()
        .map(SetupBundlePlatform::as_str)
        .unwrap_or(UNSUPPORTED_NATIVE_HOST_DEFAULT)
}

fn native_platform() -> Result<SetupBundlePlatform, SetupBundlePlatformError> {
    SupportedHostPlatform::try_from(observed_host_evidence()?)
        .map(SupportedHostPlatform::package_platform)
}

fn observed_host_evidence() -> Result<HostPlatformEvidence, SetupBundlePlatformError> {
    if env::var(ALLOW_TEST_HOST_EVIDENCE).as_deref() == Ok("1") {
        let os = env::var(TEST_HOST_OS).map_err(|_| {
            SetupBundlePlatformError::IncompleteHostEvidence {
                missing: HostEvidenceField::OperatingSystem,
            }
        })?;
        let arch = env::var(TEST_HOST_ARCH).map_err(|_| {
            SetupBundlePlatformError::IncompleteHostEvidence {
                missing: HostEvidenceField::Architecture,
            }
        })?;
        return HostPlatformEvidence::parse(&os, &arch);
    }
    HostPlatformEvidence::parse(env::consts::OS, env::consts::ARCH)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct HostPlatformEvidence {
    operating_system: HostOperatingSystem,
    architecture: HostArchitecture,
}

impl HostPlatformEvidence {
    fn parse(os: &str, arch: &str) -> Result<Self, SetupBundlePlatformError> {
        Ok(Self {
            operating_system: HostOperatingSystem::parse(os)?,
            architecture: HostArchitecture::parse(arch)?,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HostOperatingSystem {
    Macos,
    Linux,
}

impl HostOperatingSystem {
    fn parse(value: &str) -> Result<Self, SetupBundlePlatformError> {
        match value {
            "macos" => Ok(Self::Macos),
            "linux" => Ok(Self::Linux),
            _ => Err(SetupBundlePlatformError::UnsupportedHostOperatingSystem {
                observed: value.to_string(),
            }),
        }
    }
}

impl fmt::Display for HostOperatingSystem {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::Macos => "macos",
            Self::Linux => "linux",
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HostArchitecture {
    Arm64,
    X64,
}

impl HostArchitecture {
    fn parse(value: &str) -> Result<Self, SetupBundlePlatformError> {
        match value {
            "aarch64" => Ok(Self::Arm64),
            "x86_64" => Ok(Self::X64),
            _ => Err(SetupBundlePlatformError::UnsupportedHostArchitecture {
                observed: value.to_string(),
            }),
        }
    }
}

impl fmt::Display for HostArchitecture {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::Arm64 => "aarch64",
            Self::X64 => "x86_64",
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SupportedHostPlatform {
    MacosArm64,
    MacosX64,
    LinuxX64,
}

impl SupportedHostPlatform {
    fn package_platform(self) -> SetupBundlePlatform {
        match self {
            Self::MacosArm64 => SetupBundlePlatform::MacosArm64,
            Self::MacosX64 => SetupBundlePlatform::MacosX64,
            Self::LinuxX64 => SetupBundlePlatform::LinuxX64,
        }
    }
}

impl TryFrom<HostPlatformEvidence> for SupportedHostPlatform {
    type Error = SetupBundlePlatformError;

    fn try_from(evidence: HostPlatformEvidence) -> Result<Self, Self::Error> {
        match (evidence.operating_system, evidence.architecture) {
            (HostOperatingSystem::Macos, HostArchitecture::Arm64) => Ok(Self::MacosArm64),
            (HostOperatingSystem::Macos, HostArchitecture::X64) => Ok(Self::MacosX64),
            (HostOperatingSystem::Linux, HostArchitecture::X64) => Ok(Self::LinuxX64),
            (operating_system, architecture) => {
                Err(SetupBundlePlatformError::UnsupportedHostCombination {
                    operating_system,
                    architecture,
                })
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HostEvidenceField {
    OperatingSystem,
    Architecture,
}

impl fmt::Display for HostEvidenceField {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::OperatingSystem => "operating system",
            Self::Architecture => "architecture",
        })
    }
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum SetupBundlePlatformError {
    IncompleteHostEvidence {
        missing: HostEvidenceField,
    },
    UnsupportedHostOperatingSystem {
        observed: String,
    },
    UnsupportedHostArchitecture {
        observed: String,
    },
    UnsupportedHostCombination {
        operating_system: HostOperatingSystem,
        architecture: HostArchitecture,
    },
    UnsupportedTarget {
        observed: String,
    },
}

impl fmt::Display for SetupBundlePlatformError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::IncompleteHostEvidence { missing } => {
                write!(formatter, "Package host evidence is missing {missing}.")
            }
            Self::UnsupportedHostOperatingSystem { observed } => write!(
                formatter,
                "Package host operating system `{observed}` is unsupported."
            ),
            Self::UnsupportedHostArchitecture { observed } => write!(
                formatter,
                "Package host architecture `{observed}` is unsupported."
            ),
            Self::UnsupportedHostCombination {
                operating_system,
                architecture,
            } => write!(
                formatter,
                "Package host `{operating_system}/{architecture}` has no supported bundle target."
            ),
            Self::UnsupportedTarget { observed } => write!(
                formatter,
                "Package target `{observed}` is unsupported; expected macos-arm64, macos-x64, or linux-x64."
            ),
        }
    }
}

impl std::error::Error for SetupBundlePlatformError {}
