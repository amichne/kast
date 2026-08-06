#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ReleaseDigest(String);

impl ReleaseDigest {
    fn parse(value: String) -> std::result::Result<Self, String> {
        if value.len() == 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            Ok(Self(value))
        } else {
            Err("release digest must be 64 lowercase hexadecimal characters".to_string())
        }
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

impl Serialize for ReleaseDigest {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for ReleaseDigest {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        Self::parse(String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct InstalledReleasePin {
    pub install_root: String,
    pub release_root: String,
    pub release_digest: ReleaseDigest,
    pub receipt_path: String,
}
