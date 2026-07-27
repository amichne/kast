#[derive(Clone, Copy)]
enum SymbolDiscoveryFamily {
    Type,
    Callable,
}

pub(crate) fn rank_symbol_discovery(
    exact_name: Option<&str>,
    preferred_names: &BTreeSet<String>,
    query_terms: &[String],
    documents: Vec<SymbolDiscoveryDocument>,
) -> Vec<SymbolDiscoveryResult> {
    struct RankedDocument {
        document: SymbolDiscoveryDocument,
        score: usize,
        reasons: Vec<SymbolDiscoveryReason>,
    }

    let query_term_set = query_terms.iter().cloned().collect::<BTreeSet<_>>();
    let requested_family =
        if query_term_set.iter().any(|term| matches!(term.as_str(), "type" | "model")) {
            Some(SymbolDiscoveryFamily::Type)
        } else if query_term_set
            .iter()
            .any(|term| matches!(term.as_str(), "function" | "helper" | "method" | "callable"))
        {
            Some(SymbolDiscoveryFamily::Callable)
        } else {
            None
        };
    let mut ranked = documents
        .into_iter()
        .filter(|document| {
            exact_name.is_none_or(|name| document.simple_name.eq_ignore_ascii_case(name))
        })
        .filter(|document| {
            requested_family.is_none_or(|family| {
                let kind = document
                    .fields
                    .iter()
                    .find(|field| field.name == "declarationKind")
                    .map(|field| field.value.as_str())
                    .unwrap_or_default();
                match family {
                    SymbolDiscoveryFamily::Type => {
                        matches!(kind, "CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS")
                    }
                    SymbolDiscoveryFamily::Callable => matches!(
                        kind,
                        "FUNCTION"
                            | "MEMBER_FUNCTION"
                            | "CONSTRUCTOR"
                            | "GETTER"
                            | "SETTER"
                    ),
                }
            })
        })
        .filter_map(|document| {
            let exact = exact_name
                .into_iter()
                .chain(preferred_names.iter().map(String::as_str))
                .any(|name| document.simple_name.eq_ignore_ascii_case(name));
            let exact_member = document
                .fields
                .iter()
                .find(|field| field.name == "exactMember" && !field.value.is_empty())
                .map(|field| field.value.clone());
            let mut lexical_matches = document
                .fields
                .iter()
                .filter(|field| field.name != "exactMember")
                .flat_map(|field| lexical_field_matches(query_terms, field.name, &field.value))
                .collect::<Vec<_>>();
            lexical_matches.sort_by(|left, right| {
                (left.field, &left.term).cmp(&(right.field, &right.term))
            });
            lexical_matches.dedup_by(|left, right| {
                left.field == right.field && left.term == right.term
            });
            let graph_terms = document
                .graph_terms
                .intersection(&query_term_set)
                .cloned()
                .collect::<Vec<_>>();
            if !exact && exact_member.is_none() && lexical_matches.is_empty() && graph_terms.is_empty()
            {
                return None;
            }
            let mut reasons = Vec::new();
            if exact {
                reasons.push(SymbolDiscoveryReason {
                    field: "exactName",
                    terms: vec![document.simple_name.clone()],
                    score: 180,
                });
            }
            if let Some(exact_member) = exact_member {
                reasons.push(SymbolDiscoveryReason {
                    field: "exactMember",
                    terms: vec![exact_member],
                    score: 300,
                });
            }
            let mut lexical_by_field = BTreeMap::<&'static str, (Vec<String>, usize)>::new();
            for lexical_match in lexical_matches {
                let weight: usize = match lexical_match.field {
                    "name" => 50,
                    "qualifiedName" => 18,
                    "signature" => 8,
                    "parameterTypes" => 12,
                    "receiverType" => 16,
                    "returnType" => 6,
                    "annotations" => 10,
                    "scope" => 6,
                    "declarationKind" => 15,
                    "declarationText" => 12,
                    _ => 1,
                };
                let entry = lexical_by_field.entry(lexical_match.field).or_default();
                entry.0.push(lexical_match.term);
                entry.1 += if lexical_match.match_type == "TOKEN" {
                    weight
                } else {
                    weight.div_ceil(2)
                };
            }
            for (field, (terms, score)) in lexical_by_field {
                reasons.push(SymbolDiscoveryReason {
                    field,
                    score,
                    terms,
                });
            }
            if !graph_terms.is_empty() {
                reasons.push(SymbolDiscoveryReason {
                    field: "compilerNeighbors",
                    score: graph_terms.len() * 8,
                    terms: graph_terms,
                });
            }
            let score = reasons.iter().map(|reason| reason.score).sum();
            Some(RankedDocument { document, score, reasons })
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        right
            .score
            .cmp(&left.score)
            .then_with(|| left.document.sort_key.cmp(&right.document.sort_key))
    });
    ranked
        .into_iter()
        .enumerate()
        .map(|(index, ranked)| SymbolDiscoveryResult {
            identity: ranked.document.identity,
            rank: index + 1,
            score: ranked.score,
            reasons: ranked.reasons,
        })
        .collect()
}

fn query_terms(query: &str) -> Vec<String> {
    lexical_tokens(query)
        .into_iter()
        .filter(|term| {
            term.len() >= 2
                && !matches!(
                    term.as_str(),
                    "a" | "an"
                        | "and"
                        | "are"
                        | "declaration"
                        | "does"
                        | "exact"
                        | "exactly"
                        | "find"
                        | "from"
                        | "helper"
                        | "in"
                        | "kotlin"
                        | "method"
                        | "model"
                        | "of"
                        | "one"
                        | "resolve"
                        | "that"
                        | "the"
                        | "to"
                        | "type"
                        | "what"
                        | "which"
                        | "with"
                        | "without"
                )
        })
        .collect()
}

fn lexical_rank_score(matches: &[LexicalMatch]) -> f64 {
    let mut term_weights = BTreeMap::<&str, f64>::new();
    for lexical_match in matches {
        let weight = match lexical_match.field {
            "name" | "fq_names.fq_name" => 1.0,
            "qualifiedName" => 0.8,
            "identifier_paths.identifier" | "signature" => 0.6,
            "annotations" | "parameterTypes" | "receiverType" | "returnType" => 0.4,
            "declarationKind" => 0.3,
            "file_path" | "import_fq_name" | "scope" => 0.2,
            _ => 0.1,
        };
        term_weights
            .entry(&lexical_match.term)
            .and_modify(|current| *current = current.max(weight))
            .or_insert(weight);
    }
    (term_weights.values().sum::<f64>() / 5.0).min(1.0)
}

fn lexical_field_matches(
    terms: &[String],
    field: &'static str,
    evidence: &str,
) -> Vec<LexicalMatch> {
    let field_tokens = lexical_tokens(evidence);
    let lowered = evidence.to_ascii_lowercase();
    terms
        .iter()
        .filter_map(|term| {
            if field_tokens.iter().any(|token| token == term) {
                Some(LexicalMatch {
                    field,
                    term: term.clone(),
                    match_type: "TOKEN",
                    evidence: evidence.to_string(),
                })
            } else if lowered.contains(term) {
                Some(LexicalMatch {
                    field,
                    term: term.clone(),
                    match_type: "LIKE",
                    evidence: evidence.to_string(),
                })
            } else {
                None
            }
        })
        .collect()
}

fn lexical_tokens(value: &str) -> Vec<String> {
    let chars: Vec<char> = value.chars().collect();
    let mut tokens = Vec::new();
    let mut current = String::new();
    for (index, ch) in chars.iter().copied().enumerate() {
        if !ch.is_ascii_alphanumeric() {
            push_lexical_token(&mut tokens, &mut current);
            continue;
        }
        if let Some(previous) = current.chars().last()
            && is_camel_boundary(previous, ch, chars.get(index + 1).copied())
        {
            push_lexical_token(&mut tokens, &mut current);
        }
        current.push(ch);
    }
    push_lexical_token(&mut tokens, &mut current);
    tokens
}

fn is_camel_boundary(previous: char, current: char, next: Option<char>) -> bool {
    (previous.is_ascii_lowercase() && current.is_ascii_uppercase())
        || (previous.is_ascii_digit() && current.is_ascii_uppercase())
        || (previous.is_ascii_uppercase()
            && current.is_ascii_uppercase()
            && next.is_some_and(|ch| ch.is_ascii_lowercase()))
}

fn push_lexical_token(tokens: &mut Vec<String>, current: &mut String) {
    if current.is_empty() {
        return;
    }
    let token = current.to_ascii_lowercase();
    if !tokens.contains(&token) {
        tokens.push(token);
    }
    current.clear();
}

fn simple_name(fq_name: &str) -> &str {
    fq_name.rsplit('.').next().unwrap_or(fq_name)
}

fn compose_path(workspace_root: &Path, relative_dir: &str, filename: &str) -> String {
    let path = if let Some(absolute) = relative_dir.strip_prefix("__kast_abs__/") {
        PathBuf::from(absolute).join(filename)
    } else {
        let relative = relative_dir
            .strip_prefix("__kast_rel__/")
            .unwrap_or(relative_dir);
        relative
            .split('/')
            .filter(|segment| !segment.is_empty())
            .fold(workspace_root.to_path_buf(), |path, segment| {
                path.join(segment)
            })
            .join(filename)
    };
    config::normalize(path).display().to_string()
}

fn relative_path(relative_dir: &str, filename: &str) -> String {
    let relative = relative_dir
        .strip_prefix("__kast_rel__/")
        .or_else(|| relative_dir.strip_prefix("__kast_abs__/"))
        .unwrap_or(relative_dir);
    relative
        .split('/')
        .filter(|segment| !segment.is_empty())
        .fold(PathBuf::new(), |path, segment| path.join(segment))
        .join(filename)
        .display()
        .to_string()
}

fn schema_is_current(conn: &Connection) -> Result<bool> {
    let version = conn
        .query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
            row.get::<_, i64>(0)
        })
        .optional()
        .map_err(sql_error)?;
    Ok(version == Some(SOURCE_INDEX_SCHEMA_VERSION) && required_tables_exist(conn)?)
}

fn required_tables_exist(conn: &Connection) -> Result<bool> {
    for table in [
        "path_prefixes",
        "fq_names",
        "symbol_references",
        "identifier_paths",
        "file_metadata",
        "file_manifest",
        "declarations",
    ] {
        if !table_exists(conn, table)? {
            return Ok(false);
        }
    }
    Ok(true)
}

fn table_exists(conn: &Connection, table: &str) -> Result<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        params![table],
        |_| Ok(true),
    )
    .optional()
    .map(|value| value.unwrap_or(false))
    .map_err(sql_error)
}

fn edge_filter_sql(graph: &SymbolQueryGraph) -> &'static str {
    if graph.edge_kinds.is_empty() {
        ""
    } else {
        "AND instr(',' || ? || ',', ',' || refs.edge_kind || ',') > 0"
    }
}

fn graph_includes_inheritance(graph: &SymbolQueryGraph) -> bool {
    graph.edge_kinds.is_empty() || graph.edge_kinds.iter().any(|kind| kind == "INHERITANCE")
}

fn default_limit() -> usize {
    25
}

fn default_graph_direction() -> String {
    "BOTH".to_string()
}

fn default_graph_depth() -> usize {
    1
}

fn default_graph_max_edges() -> usize {
    10
}

fn sql_error(error: rusqlite::Error) -> CliError {
    CliError::new("SQLITE_ERROR", error.to_string())
}
