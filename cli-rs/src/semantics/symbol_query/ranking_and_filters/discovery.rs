pub(crate) fn rank_symbol_discovery(
    exact_name: Option<&str>,
    preferred_names: &BTreeSet<String>,
    intent: SymbolDiscoveryIntent,
    query_terms: &[String],
    documents: Vec<SymbolDiscoveryDocument>,
) -> Vec<SymbolDiscoveryResult> {
    struct RankedDocument {
        document: SymbolDiscoveryDocument,
        score: usize,
        reasons: Vec<SymbolDiscoveryReason>,
    }

    let query_term_set = query_terms.iter().cloned().collect::<BTreeSet<_>>();
    let mut ranked = documents
        .into_iter()
        .filter(|document| {
            exact_name.is_none_or(|name| document.simple_name.eq_ignore_ascii_case(name))
        })
        .filter(|document| {
            intent.target_family().is_none_or(|family| {
                document
                    .fields
                    .iter()
                    .find(|field| field.name == "declarationKind")
                    .is_some_and(|field| family.admits(&field.value))
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
            let exact_return_type = document
                .fields
                .iter()
                .find(|field| field.name == "returnType")
                .and_then(|field| {
                    preferred_names
                        .iter()
                        .find(|name| type_mentions(&field.value, name))
                })
                .cloned();
            let exact_returning_callable = document
                .fields
                .iter()
                .find(|field| field.name == "returningCallables")
                .and_then(|field| {
                    preferred_names
                        .iter()
                        .find(|name| named_evidence_mentions(&field.value, name))
                })
                .cloned();
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
            if !exact
                && exact_member.is_none()
                && exact_return_type.is_none()
                && exact_returning_callable.is_none()
                && lexical_matches.is_empty()
                && graph_terms.is_empty()
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
            if let Some(exact_return_type) = exact_return_type {
                reasons.push(SymbolDiscoveryReason {
                    field: "exactReturnType",
                    terms: vec![exact_return_type],
                    score: 300,
                });
            }
            if let Some(exact_returning_callable) = exact_returning_callable {
                reasons.push(SymbolDiscoveryReason {
                    field: "exactReturningCallable",
                    terms: vec![exact_returning_callable],
                    score: 300,
                });
            }
            let mut lexical_by_field = BTreeMap::<&'static str, (Vec<String>, usize)>::new();
            for lexical_match in lexical_matches {
                let weight: usize = match lexical_match.field {
                    "name" => 50,
                    "qualifiedName" | "returningCallables" => 18,
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
            Some(RankedDocument {
                document,
                score,
                reasons,
            })
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
