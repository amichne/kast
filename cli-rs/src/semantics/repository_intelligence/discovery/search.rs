fn best_question_nodes(
    connection: &Connection,
    question: &str,
    execution_scope: &RepositoryExecutionScope,
    forced_name: Option<&str>,
) -> Result<Vec<RepositoryNode>> {
    require_semantic_graph_tables(connection)?;
    let name = match forced_name {
        Some(name) => Some(name.to_string()),
        None => mentioned_callable_names(connection, question)?
            .first()
            .map(|(_, name)| name.clone())
            .or_else(|| likely_declaration_term(question).map(str::to_string)),
    };
    let Some(name) = name else {
        return Ok(Vec::new());
    };
    let candidates =
        execution_scope.admit_nodes(load_repository_node(connection, "symbol.name = ?1", name)?);
    if candidates.is_empty() {
        return Ok(candidates);
    }
    let scores = candidates
        .iter()
        .map(|candidate| repository_node_score(candidate, question))
        .collect::<Vec<_>>();
    let best = scores.iter().copied().max().unwrap_or(0);
    let mut selected = candidates
        .into_iter()
        .zip(scores)
        .filter_map(|(candidate, score)| (score == best).then_some(candidate))
        .collect::<Vec<_>>();
    selected.sort_by(|left, right| left.canonical_key.cmp(&right.canonical_key));
    Ok(selected)
}

fn require_semantic_graph_tables(connection: &Connection) -> Result<()> {
    let table_count: i64 = connection
        .query_row(
            "SELECT COUNT(*)
             FROM sqlite_master
             WHERE type = 'table'
               AND name IN ('semantic_symbols', 'semantic_edge_occurrences')",
            [],
            |row| row.get(0),
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    if table_count == 2 {
        return Ok(());
    }
    let mut error = CliError::new(
        "REPOSITORY_INDEX_INVALID",
        "repository index is missing required compiler semantic tables",
    );
    error.details.insert(
        "remedy".to_string(),
        "Request the semantic graph operation again after compiler-backed graph evidence has committed for the current source revision."
            .to_string(),
    );
    Err(error)
}

fn mentioned_callable_names(
    connection: &Connection,
    question: &str,
) -> Result<Vec<(usize, String)>> {
    require_semantic_graph_tables(connection)?;
    let mut statement = connection
        .prepare(
            "SELECT DISTINCT name
             FROM semantic_symbols
             WHERE kind IN ('FUNCTION', 'MEMBER_FUNCTION', 'CONSTRUCTOR', 'GETTER', 'SETTER')
             ORDER BY name",
        )
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let names = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let question_lower = question.to_ascii_lowercase();
    let mut mentions = names
        .into_iter()
        .filter(|name| !is_query_verb(name))
        .filter_map(|name| {
            identifier_position(&question_lower, &name.to_ascii_lowercase())
                .map(|position| (position, name))
        })
        .collect::<Vec<_>>();
    mentions.sort_by(|left, right| {
        left.0
            .cmp(&right.0)
            .then_with(|| right.1.len().cmp(&left.1.len()))
            .then_with(|| left.1.cmp(&right.1))
    });
    mentions.dedup_by(|left, right| left.1 == right.1);
    Ok(mentions)
}

fn repository_symbol_mentions(
    connection: &Connection,
    question: &str,
) -> Result<Vec<(usize, String)>> {
    let mentions = mentioned_callable_names(connection, question)?;
    let dotted = dotted_member_name(question);
    let explicit = mentions
        .iter()
        .filter(|(_, name)| {
            name.chars().any(char::is_uppercase) || dotted.as_deref() == Some(name.as_str())
        })
        .cloned()
        .collect::<Vec<_>>();
    Ok(if explicit.is_empty() {
        mentions
    } else {
        explicit
    })
}

fn is_query_verb(name: &str) -> bool {
    matches!(
        name.to_ascii_lowercase().as_str(),
        "find" | "list" | "resolve" | "show" | "trace" | "reach" | "used" | "contain" | "connect"
    )
}

fn dotted_member_name(question: &str) -> Option<String> {
    question
        .split_whitespace()
        .map(|token| {
            token.trim_matches(|character: char| !(character.is_alphanumeric() || character == '_'))
        })
        .filter_map(|token| token.rsplit_once('.').map(|(_, member)| member))
        .map(|member| {
            member
                .trim_matches(|character: char| !(character.is_alphanumeric() || character == '_'))
                .to_string()
        })
        .find(|member| !member.is_empty())
}

fn identifier_position(haystack: &str, needle: &str) -> Option<usize> {
    haystack.match_indices(needle).find_map(|(start, _)| {
        let end = start + needle.len();
        let before = haystack[..start].chars().next_back();
        let after = haystack[end..].chars().next();
        let boundary = |character: Option<char>| {
            character.is_none_or(|character| !(character.is_alphanumeric() || character == '_'))
        };
        (boundary(before) && boundary(after)).then_some(start)
    })
}

fn load_repository_node<T: rusqlite::ToSql>(
    connection: &Connection,
    predicate: &str,
    value: T,
) -> Result<Vec<RepositoryNode>> {
    let sql = format!(
        "SELECT symbol.id,
                symbol.stable_key,
                symbol.kind,
                symbol.name,
                symbol.fq_name,
                symbol.signature,
                symbol.visibility,
                symbol.modality,
                symbol.origin,
                file.path,
                CASE
                    WHEN owner.name = 'Companion' THEN outer_owner.name
                    ELSE owner.name
                END,
                receiver_type.classifier,
                receiver_type.debug_text,
                return_type.classifier,
                return_type.debug_text,
                symbol.start_offset,
                symbol.end_offset,
                symbol.line,
                symbol.is_expect,
                symbol.is_actual,
                symbol.is_override,
                symbol.is_sealed,
                symbol.is_delegated,
                COALESCE((
                    SELECT json_group_array(annotation_name)
                    FROM (
                        SELECT annotation_name
                        FROM semantic_symbol_annotations
                        WHERE symbol_id = symbol.id
                        ORDER BY annotation_name
                    )
                ), '[]')
         FROM semantic_symbols symbol
         JOIN semantic_files file ON file.id = symbol.file_id
         LEFT JOIN semantic_symbols owner ON owner.id = symbol.owner_id
         LEFT JOIN semantic_symbols outer_owner ON outer_owner.id = owner.owner_id
         LEFT JOIN semantic_types receiver_type ON receiver_type.id = symbol.receiver_type_id
         LEFT JOIN semantic_types return_type ON return_type.id = symbol.return_type_id
         WHERE {predicate}
         ORDER BY symbol.stable_key"
    );
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    let rows = statement
        .query_map([value], repository_node_from_row)
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| CliError::new("REPOSITORY_INDEX_UNAVAILABLE", error.to_string()))
}

fn repository_node_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<RepositoryNode> {
    let signature = row.get::<_, Option<String>>(5)?;
    let annotations_json = row.get::<_, String>(23)?;
    let annotations = serde_json::from_str(&annotations_json).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(23, Type::Text, Box::new(error))
    })?;
    Ok(RepositoryNode {
        database_id: row.get(0)?,
        canonical_key: row.get(1)?,
        kind: row.get(2)?,
        name: row.get(3)?,
        fq_name: row.get(4)?,
        parameter_types: signature
            .as_deref()
            .map(parameter_types_from_signature)
            .unwrap_or_default(),
        signature,
        visibility: row.get(6)?,
        modality: row.get(7)?,
        origin: row.get(8)?,
        path: row.get(9)?,
        gradle_projects: Vec::new(),
        source_sets: Vec::new(),
        owner_name: row.get(10)?,
        receiver_type: preferred_type_name(row.get(11)?, row.get(12)?),
        return_type: preferred_type_name(row.get(13)?, row.get(14)?),
        declaration_range: RepositorySourceRange {
            start_offset: row.get(15)?,
            end_offset: row.get(16)?,
            line: row.get(17)?,
        },
        flags: RepositorySymbolFlags {
            is_expect: row.get::<_, i64>(18)? != 0,
            is_actual: row.get::<_, i64>(19)? != 0,
            is_override: row.get::<_, i64>(20)? != 0,
            is_sealed: row.get::<_, i64>(21)? != 0,
            is_delegated: row.get::<_, i64>(22)? != 0,
        },
        annotations,
        evidence_class: "compiler",
    })
}

fn preferred_type_name(classifier: Option<String>, debug_text: Option<String>) -> Option<String> {
    classifier.or(debug_text)
}

fn parameter_types_from_signature(signature: &str) -> Vec<String> {
    let Some(raw) = signature.split('|').nth(3) else {
        return Vec::new();
    };
    if raw.is_empty() {
        return Vec::new();
    }
    let mut depth = 0usize;
    let mut start = 0usize;
    let mut parameters = Vec::new();
    for (index, character) in raw.char_indices() {
        match character {
            '<' => depth += 1,
            '>' => depth = depth.saturating_sub(1),
            ',' if depth == 0 => {
                parameters.push(raw[start..index].to_string());
                start = index + 1;
            }
            _ => {}
        }
    }
    parameters.push(raw[start..].to_string());
    parameters
}

fn repository_node_score(node: &RepositoryNode, question: &str) -> usize {
    let question_compact = compact_search_text(question);
    let question_tokens = search_tokens(question);
    let mut score = 0;
    if let Some(owner) = &node.owner_name {
        let exact_member = compact_search_text(&format!("{owner}.{}", node.name));
        if question_compact.contains(&exact_member) {
            score += 200;
        }
        let owner_compact = compact_search_text(owner);
        if question_compact.contains(&owner_compact) {
            score += 80;
        }
    }
    for parameter in &node.parameter_types {
        let simple = parameter
            .rsplit('.')
            .next()
            .map(compact_search_text)
            .unwrap_or_default();
        if !simple.is_empty() && question_compact.contains(&simple) {
            score += 100;
        }
    }
    let metadata = [
        node.owner_name.as_deref(),
        node.fq_name.as_deref(),
        node.signature.as_deref(),
        Some(node.path.as_str()),
    ]
    .into_iter()
    .flatten()
    .collect::<Vec<_>>()
    .join(" ");
    score
        + search_tokens(&metadata)
            .intersection(&question_tokens)
            .count()
            * 5
}

fn compact_search_text(raw: &str) -> String {
    raw.chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn search_tokens(raw: &str) -> BTreeSet<String> {
    let mut normalized = String::new();
    let mut previous_lowercase = false;
    for character in raw.chars() {
        if !character.is_alphanumeric() {
            normalized.push(' ');
            previous_lowercase = false;
            continue;
        }
        if character.is_uppercase() && previous_lowercase {
            normalized.push(' ');
        }
        normalized.extend(character.to_lowercase());
        previous_lowercase = character.is_lowercase();
    }
    normalized
        .split_whitespace()
        .filter(|token| token.len() >= 3)
        .map(str::to_string)
        .collect()
}

fn likely_declaration_term(question: &str) -> Option<&str> {
    question
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|word| word.len() >= 3)
        .filter(|word| {
            word.chars()
                .filter(|character| character.is_uppercase())
                .count()
                >= 2
        })
        .max_by_key(|word| word.len())
}
