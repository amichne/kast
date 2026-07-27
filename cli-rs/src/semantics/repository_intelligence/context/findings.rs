fn context_gap_findings(
    targets: &[RepositoryNode],
    unresolved: &[String],
    context_nodes: &BTreeMap<RepositoryContextSource, BTreeSet<String>>,
    markdown_documents: &BTreeMap<String, String>,
    relations: &[RepositoryContextRelation],
) -> Vec<Value> {
    let mut findings = Vec::new();
    if context_nodes.contains_key(&RepositoryContextSource::Markdown) {
        for name in unresolved {
            for (source_path, content) in markdown_documents {
                if let Some(start) = content.find(name) {
                    findings.push(json!({
                        "type": "STALE_DOCUMENT_REFERENCE",
                        "sourcePath": source_path,
                        "reference": name,
                        "trigger": "explicit document identifier resolves to zero exact Kotlin identities",
                        "sourceLocation": context_location(content, start, name.len()),
                        "evidenceClass": "extracted"
                    }));
                }
            }
        }
        for target in targets
            .iter()
            .filter(|target| target.visibility == "PUBLIC")
        {
            if !relations.iter().any(|relation| {
                relation.source_kind == RepositoryContextSource::Markdown
                    && relation.target_key == target.canonical_key
            }) {
                findings.push(json!({
                    "type": "PUBLIC_API_DOCUMENTATION_GAP",
                    "targetKey": target.canonical_key,
                    "targetName": target.name,
                    "trigger": "public exact Kotlin identity has no selected Markdown relation",
                    "evidenceClass": "derived"
                }));
            }
        }
    }
    findings.sort_by_key(|finding| finding.to_string());
    findings
}

fn repository_context_relation_vocabulary() -> Vec<Value> {
    [
        RepositoryContextRelationKind::MentionsSymbol,
        RepositoryContextRelationKind::Documents,
        RepositoryContextRelationKind::ConfiguresModule,
        RepositoryContextRelationKind::DeclaresDependency,
        RepositoryContextRelationKind::Generates,
        RepositoryContextRelationKind::ConsumesSchema,
        RepositoryContextRelationKind::ImplementsProtocol,
        RepositoryContextRelationKind::Supersedes,
        RepositoryContextRelationKind::ConflictsWith,
    ]
    .into_iter()
    .map(|kind| {
        let (source_kinds, evidence_class, required_evidence) = match kind {
            RepositoryContextRelationKind::MentionsSymbol
            | RepositoryContextRelationKind::Documents => {
                (vec!["markdown", "adr"], "extracted", "source location")
            }
            RepositoryContextRelationKind::ConfiguresModule
            | RepositoryContextRelationKind::DeclaresDependency => (
                vec!["gradle", "workflow"],
                "derived",
                "module ownership and source location",
            ),
            RepositoryContextRelationKind::Generates
            | RepositoryContextRelationKind::ConsumesSchema
            | RepositoryContextRelationKind::ImplementsProtocol => (
                vec!["schema", "rust"],
                "derived",
                "named deterministic derivation and source location",
            ),
            RepositoryContextRelationKind::Supersedes
            | RepositoryContextRelationKind::ConflictsWith => (
                vec!["markdown", "adr"],
                "inferred",
                "explicit inference rule and source location",
            ),
        };
        json!({
            "kind": kind,
            "direction": "OUTGOING",
            "sourceKinds": source_kinds,
            "targetKind": "EXACT_KOTLIN_SYMBOL",
            "evidenceClass": evidence_class,
            "requiredEvidence": required_evidence
        })
    })
    .collect()
}

fn ratio(numerator: usize, denominator: usize) -> f64 {
    numerator as f64 / denominator.max(1) as f64
}
