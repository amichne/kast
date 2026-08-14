#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(transparent)]
struct VerifiedAddFileApprovalSha256(String);

impl VerifiedAddFileApprovalSha256 {
    fn for_plan(plan: &StoredVerifiedAddFilePlan) -> Self {
        let statement = format!(
            "kast-public-cli\nworkspaceRoot={}\nplanId={}\nexpectedVersion={}\n",
            plan.workspace_root,
            plan.plan_id.as_str(),
            plan.plan_version.value(),
        );
        Self(manifest::sha256_bytes(statement.as_bytes()))
    }

    fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredVerifiedAddFileApplyInFlight {
    recovery_id: crate::agent::public_protocol::VerifiedAddFileRecoveryId,
    expected_version: VerifiedAddFilePlanVersion,
    approval_evidence_sha256: VerifiedAddFileApprovalSha256,
}

impl StoredVerifiedAddFileApplyInFlight {
    fn prepare(plan: &StoredVerifiedAddFilePlan) -> Self {
        Self {
            recovery_id:
                crate::agent::public_protocol::VerifiedAddFileRecoveryId::from_plan_id(
                    &plan.plan_id,
                ),
            expected_version: plan.plan_version,
            approval_evidence_sha256: VerifiedAddFileApprovalSha256::for_plan(plan),
        }
    }

    fn admit(&self, plan: &StoredVerifiedAddFilePlan) -> Result<Self> {
        let expected = Self::prepare(plan);
        (self == &expected).then_some(expected).ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_INVALID",
                "The in-flight add-file authority did not retain its exact plan, version, and approval.",
            )
        })
    }

    fn recovery_id(&self) -> &crate::agent::public_protocol::VerifiedAddFileRecoveryId {
        &self.recovery_id
    }
}
