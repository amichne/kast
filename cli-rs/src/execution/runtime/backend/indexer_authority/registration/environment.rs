use super::registration_invalid;
use crate::config::{self, KastConfig};
use crate::error::Result;
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::collections::{BTreeMap, btree_map};
use std::ffi::{OsStr, OsString};
use std::os::unix::ffi::{OsStrExt as _, OsStringExt as _};

const IMPLICIT_JVM_OPTION_VARIABLES: [&str; 3] =
    ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub(in crate::runtime::indexer_authority) struct ServiceLaunchEnvironment {
    variables: BTreeMap<OsString, OsString>,
}

impl ServiceLaunchEnvironment {
    pub(super) fn capture(config: &KastConfig) -> Result<Self> {
        Self::from_inherited(
            config,
            config::kast_config_home().into_os_string(),
            std::env::vars_os(),
        )
        .map_err(registration_invalid)
    }

    fn from_inherited(
        config: &KastConfig,
        config_home: OsString,
        inherited: impl IntoIterator<Item = (OsString, OsString)>,
    ) -> std::result::Result<Self, &'static str> {
        let mut environment = Self::from_variables(inherited)?;
        for variable in IMPLICIT_JVM_OPTION_VARIABLES {
            environment.variables.remove(OsStr::new(variable));
        }
        environment.variables.insert(
            OsString::from("KAST_HOME"),
            config.paths.install_root.as_os_str().to_os_string(),
        );
        environment
            .variables
            .insert(OsString::from("KAST_CONFIG_HOME"), config_home);
        environment
            .variables
            .insert(OsString::from("KAST_INDEXER"), OsString::from("true"));
        environment
            .variables
            .insert(OsString::from("GIT_OPTIONAL_LOCKS"), OsString::from("0"));
        Ok(environment)
    }

    fn from_variables(
        variables: impl IntoIterator<Item = (OsString, OsString)>,
    ) -> std::result::Result<Self, &'static str> {
        let mut environment = Self {
            variables: BTreeMap::new(),
        };
        for (name, value) in variables {
            environment.insert_unique(name, value)?;
        }
        Ok(environment)
    }

    fn insert_unique(
        &mut self,
        name: OsString,
        value: OsString,
    ) -> std::result::Result<(), &'static str> {
        let name_bytes = name.as_os_str().as_bytes();
        if name_bytes.is_empty() || name_bytes.contains(&0) || name_bytes.contains(&b'=') {
            return Err("Runtime launch environment contains an invalid variable name.");
        }
        if value.as_os_str().as_bytes().contains(&0) {
            return Err("Runtime launch environment contains an invalid variable value.");
        }
        if self.variables.insert(name, value).is_some() {
            return Err("Runtime launch environment contains a duplicate variable name.");
        }
        Ok(())
    }

    #[cfg(test)]
    fn value(&self, name: &OsStr) -> Option<&OsStr> {
        self.variables.get(name).map(OsString::as_os_str)
    }
}

pub(in crate::runtime::indexer_authority) struct ServiceLaunchEnvironmentIter<'a>(
    btree_map::Iter<'a, OsString, OsString>,
);

impl<'a> Iterator for ServiceLaunchEnvironmentIter<'a> {
    type Item = (&'a OsStr, &'a OsStr);

    fn next(&mut self) -> Option<Self::Item> {
        self.0
            .next()
            .map(|(name, value)| (name.as_os_str(), value.as_os_str()))
    }
}

impl<'a> IntoIterator for &'a ServiceLaunchEnvironment {
    type Item = (&'a OsStr, &'a OsStr);
    type IntoIter = ServiceLaunchEnvironmentIter<'a>;

    fn into_iter(self) -> Self::IntoIter {
        ServiceLaunchEnvironmentIter(self.variables.iter())
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SerializedEnvironmentVariable<'a> {
    name: &'a [u8],
    value: &'a [u8],
}

impl Serialize for ServiceLaunchEnvironment {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        self.variables
            .iter()
            .map(|(name, value)| SerializedEnvironmentVariable {
                name: name.as_os_str().as_bytes(),
                value: value.as_os_str().as_bytes(),
            })
            .collect::<Vec<_>>()
            .serialize(serializer)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct SerializedEnvironmentVariableOwned {
    name: Vec<u8>,
    value: Vec<u8>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum SerializedEnvironment {
    Bytes(Vec<SerializedEnvironmentVariableOwned>),
    Legacy(BTreeMap<String, String>),
}

impl<'de> Deserialize<'de> for ServiceLaunchEnvironment {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let variables = match SerializedEnvironment::deserialize(deserializer)? {
            SerializedEnvironment::Bytes(variables) => variables
                .into_iter()
                .map(|variable| {
                    (
                        OsString::from_vec(variable.name),
                        OsString::from_vec(variable.value),
                    )
                })
                .collect::<Vec<_>>(),
            SerializedEnvironment::Legacy(variables) => variables
                .into_iter()
                .map(|(name, value)| (OsString::from(name), OsString::from(value)))
                .collect::<Vec<_>>(),
        };
        Self::from_variables(variables).map_err(serde::de::Error::custom)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::{OsStr, OsString};

    #[test]
    fn gradle_launch_environment_is_preserved_review_regression() {
        let config = KastConfig::defaults();
        let inherited = [
            (OsString::from("PATH"), OsString::from("/developer/bin")),
            (OsString::from("JAVA_HOME"), OsString::from("/jdk")),
            (
                OsString::from("GRADLE_USER_HOME"),
                OsString::from("/gradle-cache"),
            ),
            (
                OsString::from("ORG_GRADLE_PROJECT_repositoryToken"),
                OsString::from("credential"),
            ),
            (
                OsString::from("HTTPS_PROXY"),
                OsString::from("https://proxy.invalid"),
            ),
            (
                OsString::from("JAVA_TOOL_OPTIONS"),
                OsString::from("-Xmx8192m"),
            ),
            (
                OsString::from("JDK_JAVA_OPTIONS"),
                OsString::from("-Xmx7168m"),
            ),
            (OsString::from("_JAVA_OPTIONS"), OsString::from("-Xmx6144m")),
            (OsString::from("KAST_HOME"), OsString::from("/poisoned")),
            (OsString::from("KAST_INDEXER"), OsString::from("false")),
            (
                OsString::from_vec(vec![b'R', b'A', b'W', 0x80]),
                OsString::from_vec(vec![b'v', 0x81]),
            ),
        ];

        let environment = ServiceLaunchEnvironment::from_inherited(
            &config,
            OsString::from("/trusted-config"),
            inherited,
        )
        .expect("launch environment");
        let serialized = serde_json::to_vec(&environment).expect("environment JSON");
        let round_trip: ServiceLaunchEnvironment =
            serde_json::from_slice(&serialized).expect("round-trip environment");

        assert_eq!(
            environment.value(OsStr::new("PATH")),
            Some(OsStr::new("/developer/bin"))
        );
        assert_eq!(
            environment.value(OsStr::new("JAVA_HOME")),
            Some(OsStr::new("/jdk"))
        );
        assert_eq!(
            environment.value(OsStr::new("GRADLE_USER_HOME")),
            Some(OsStr::new("/gradle-cache")),
        );
        assert_eq!(
            environment.value(OsStr::new("ORG_GRADLE_PROJECT_repositoryToken")),
            Some(OsStr::new("credential")),
        );
        assert_eq!(
            environment.value(OsStr::new("HTTPS_PROXY")),
            Some(OsStr::new("https://proxy.invalid")),
        );
        for variable in IMPLICIT_JVM_OPTION_VARIABLES {
            assert_eq!(environment.value(OsStr::new(variable)), None);
        }
        assert_eq!(
            environment.value(OsStr::new("KAST_HOME")),
            Some(config.paths.install_root.as_os_str()),
        );
        assert_eq!(
            environment.value(OsStr::new("KAST_CONFIG_HOME")),
            Some(OsStr::new("/trusted-config")),
        );
        assert_eq!(
            environment.value(OsStr::new("KAST_INDEXER")),
            Some(OsStr::new("true"))
        );
        assert_eq!(
            environment.value(OsStr::new("GIT_OPTIONAL_LOCKS")),
            Some(OsStr::new("0"))
        );
        assert_eq!(
            round_trip.value(OsStr::from_bytes(&[b'R', b'A', b'W', 0x80])),
            Some(OsStr::from_bytes(&[b'v', 0x81])),
        );
        assert_eq!(round_trip, environment);

        let reversed = ServiceLaunchEnvironment::from_inherited(
            &config,
            OsString::from("/trusted-config"),
            [
                (OsString::from("B"), OsString::from("2")),
                (OsString::from("A"), OsString::from("1")),
            ],
        )
        .expect("ordered launch environment");
        let ordered = ServiceLaunchEnvironment::from_inherited(
            &config,
            OsString::from("/trusted-config"),
            [
                (OsString::from("A"), OsString::from("1")),
                (OsString::from("B"), OsString::from("2")),
            ],
        )
        .expect("ordered launch environment");
        assert_eq!(
            serde_json::to_vec(&reversed).expect("reversed JSON"),
            serde_json::to_vec(&ordered).expect("ordered JSON"),
        );
    }
}
