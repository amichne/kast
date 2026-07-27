fn markdown_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let (start, length, direct_score) = context_target_text_match(content, target)?;
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Markdown,
        RepositoryContextRelationKind::Documents,
        "extracted",
        None,
        start,
        length,
        direct_score,
    ))
}

fn gradle_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
    ownership: &RepositoryFileOwnership,
) -> Option<RepositoryContextCandidate> {
    let project = ownership
        .gradle_projects
        .iter()
        .find(|project| relative == gradle_build_script_path(project))?;
    let project_id = canonical_gradle_project(project);
    let source_sets = ownership
        .source_sets
        .iter()
        .filter(|source_set| source_set.project() == project)
        .map(canonical_gradle_source_set)
        .collect::<Vec<_>>();
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Gradle,
        RepositoryContextRelationKind::ConfiguresModule,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "SEMANTIC_OWNERSHIP_TO_GRADLE_BUILD",
            facts: json!({"gradleProject": project_id, "sourceSets": source_sets}),
        }),
        0,
        0,
        400,
    ))
}

fn schema_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    let operation = target.name.strip_suffix("Operation")?;
    let slug = kebab_identifier(operation);
    let start = content.find(&slug).or_else(|| relative.find(&slug))?;
    let direct_score = if relative.ends_with(&format!("/requests/raw/{slug}/request.schema.json")) {
        500
    } else {
        250
    };
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Schema,
        RepositoryContextRelationKind::ImplementsProtocol,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "RAW_RPC_METHOD_TO_BACKEND_OPERATION",
            facts: json!({"operation": slug, "symbol": target.canonical_key}),
        }),
        start.min(content.len()),
        slug.len(),
        direct_score,
    ))
}

fn workflow_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
    ownership: &RepositoryFileOwnership,
) -> Option<RepositoryContextCandidate> {
    let (project, needle, start) = ownership.gradle_projects.iter().find_map(|project| {
        let project_path = project.project_path().as_str();
        (project_path != ":")
            .then(|| format!("{project_path}:"))
            .and_then(|needle| content.find(&needle).map(|start| (project, needle, start)))
    })?;
    let project_id = canonical_gradle_project(project);
    let source_sets = ownership
        .source_sets
        .iter()
        .filter(|source_set| source_set.project() == project)
        .map(canonical_gradle_source_set)
        .collect::<Vec<_>>();
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Workflow,
        RepositoryContextRelationKind::ConfiguresModule,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "WORKFLOW_GRADLE_TASK_TO_SEMANTIC_MODULE",
            facts: json!({"gradleProject": project_id, "sourceSets": source_sets}),
        }),
        start,
        needle.len(),
        350,
    ))
}

fn gradle_build_script_path(project: &BuildQualifiedGradleProjectIdentity) -> String {
    let mut path = project.build_root().as_path().to_path_buf();
    for component in project
        .project_path()
        .as_str()
        .trim_start_matches(':')
        .split(':')
        .filter(|component| !component.is_empty())
    {
        path.push(component);
    }
    path.push("build.gradle.kts");
    path.to_string_lossy().into_owned()
}

fn rust_context_relation(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
) -> Option<RepositoryContextCandidate> {
    if target.name != "SqliteSourceIndexStore" {
        return None;
    }
    let needle = "semantic_edge_occurrences";
    let start = content.find(needle)?;
    Some(context_candidate(
        question,
        relative,
        content,
        target,
        RepositoryContextSource::Rust,
        RepositoryContextRelationKind::ConsumesSchema,
        "derived",
        Some(RepositoryContextDerivation {
            rule: "SHARED_SEMANTIC_EDGE_SCHEMA",
            facts: json!({
                "table": needle,
                "schemaOwner": target.canonical_key
            }),
        }),
        start,
        needle.len(),
        300,
    ))
}

#[allow(clippy::too_many_arguments)]
fn context_candidate(
    question: &str,
    relative: &str,
    content: &str,
    target: &RepositoryNode,
    source_kind: RepositoryContextSource,
    kind: RepositoryContextRelationKind,
    evidence_class: &'static str,
    derivation: Option<RepositoryContextDerivation>,
    start: usize,
    length: usize,
    direct_score: usize,
) -> RepositoryContextCandidate {
    let target_relevance = discovery_query_terms(question)
        .intersection(&discovery_lexical_tokens(&target.name))
        .count()
        * 50;
    RepositoryContextCandidate {
        score: direct_score
            + target_relevance
            + context_relevance_score(question, relative, content),
        relation: RepositoryContextRelation {
            source_path: relative.to_string(),
            source_kind,
            target_key: target.canonical_key.clone(),
            target_name: target.name.clone(),
            kind,
            direction: RepositoryDirection::Outgoing,
            source_location: context_location(content, start, length),
            evidence_class,
            derivation,
        },
    }
}

fn context_target_text_match(
    content: &str,
    target: &RepositoryNode,
) -> Option<(usize, usize, usize)> {
    if let Some(start) = identifier_position(content, &target.name) {
        return Some((start, target.name.len(), 500));
    }
    if let Some(fq_name) = target.fq_name.as_deref()
        && let Some(start) = identifier_position(content, fq_name)
    {
        return Some((start, fq_name.len(), 550));
    }
    if let Some(start) = content.find(&target.path) {
        return Some((start, target.path.len(), 750));
    }
    let components = target.path.split('/').collect::<Vec<_>>();
    for count in (3..components.len()).rev() {
        let prefix = components[..count].join("/");
        if let Some(start) = content.find(&prefix) {
            return Some((start, prefix.len(), 650 + count));
        }
    }
    None
}

fn context_relevance_score(question: &str, relative: &str, content: &str) -> usize {
    let query_terms = discovery_query_terms(question);
    let value_terms = discovery_lexical_tokens(&format!("{relative} {content}"));
    query_terms.intersection(&value_terms).count().min(25) * 4
}

fn context_location(content: &str, start: usize, length: usize) -> RepositoryContextLocation {
    let start = start.min(content.len());
    RepositoryContextLocation {
        line: content[..start]
            .bytes()
            .filter(|byte| *byte == b'\n')
            .count()
            + 1,
        start_offset: start,
        end_offset: start.saturating_add(length).min(content.len()),
    }
}

fn kebab_identifier(value: &str) -> String {
    let mut output = String::new();
    for (index, character) in value.chars().enumerate() {
        if character.is_uppercase() {
            if index > 0 {
                output.push('-');
            }
            output.extend(character.to_lowercase());
        } else {
            output.push(character);
        }
    }
    output
}
