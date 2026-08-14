use serde::{Deserialize, Serialize};
use uuid::{Uuid, Version};

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum PlanId {
    Legacy(Uuid),
    VerifiedAddFile(VerifiedAddFilePlanId),
    VerifiedAddDeclaration(VerifiedAddDeclarationPlanId),
}

impl PlanId {
    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        if let Some(id) = parse_v4_id(value) {
            return Ok(Self::Legacy(id));
        }
        if let Some(id) = VerifiedAddFilePlanId::parse(value) {
            return Ok(Self::VerifiedAddFile(id));
        }
        VerifiedAddDeclarationPlanId::parse(value)
            .map(Self::VerifiedAddDeclaration)
            .ok_or("Plan IDs must be canonical UUIDs, af-prefixed add-file IDs, or verified add-declaration SHA-256 IDs.")
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub(crate) struct VerifiedAddFilePlanId(String);

impl VerifiedAddFilePlanId {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        canonical_add_file_id(value).then(|| Self(value.to_string()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub(crate) struct VerifiedAddFileRecoveryId(String);

impl VerifiedAddFileRecoveryId {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        canonical_add_file_id(value).then(|| Self(value.to_string()))
    }

    pub(crate) fn from_plan_id(plan_id: &VerifiedAddFilePlanId) -> Self {
        Self(plan_id.0.clone())
    }

    pub(crate) fn originating_plan_id(&self) -> VerifiedAddFilePlanId {
        VerifiedAddFilePlanId(self.0.clone())
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub(crate) struct VerifiedAddDeclarationPlanId(String);

impl VerifiedAddDeclarationPlanId {
    pub(crate) fn parse(value: &str) -> Option<Self> {
        (value.len() == 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()))
        .then(|| Self(value.to_string()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum RecoveryId {
    Legacy(Uuid),
    VerifiedAddFile(VerifiedAddFileRecoveryId),
}

impl RecoveryId {
    pub(crate) fn parse(value: &str) -> Result<Self, &'static str> {
        if let Some(id) = parse_v4_id(value) {
            return Ok(Self::Legacy(id));
        }
        VerifiedAddFileRecoveryId::parse(value)
            .map(Self::VerifiedAddFile)
            .ok_or("Recovery IDs must be canonical UUIDs or af-prefixed add-file recovery IDs returned by Kast.")
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ExternalFailureId(String);

impl ExternalFailureId {
    pub(crate) fn parse(value: String) -> Result<Self, &'static str> {
        Uuid::parse_str(&value)
            .ok()
            .filter(|parsed| parsed.hyphenated().to_string() == value)
            .map(|_| Self(value))
            .ok_or("External failure IDs must be canonical lowercase UUIDs returned by Kast.")
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

fn canonical_add_file_id(value: &str) -> bool {
    value.strip_prefix("af-").is_some_and(|digest| {
        digest.len() == 64
            && digest
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    })
}

fn parse_v4_id(value: &str) -> Option<Uuid> {
    Uuid::parse_str(value)
        .ok()
        .filter(|id| id.get_version() == Some(Version::Random))
        .filter(|id| id.hyphenated().to_string() == value)
}
