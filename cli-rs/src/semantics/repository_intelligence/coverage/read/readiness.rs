pub fn semantic_graph_readiness(workspace_root: &Path) -> SemanticGraphReadiness {
    match read_coverage(
        workspace_root,
        RepositoryScope {
            language: Some("kotlin".to_string()),
            ..RepositoryScope::default()
        },
    ) {
        Ok(snapshot) => {
            let coverage = snapshot.coverage;
            SemanticGraphReadiness {
                state: if coverage.complete {
                    SemanticGraphReadinessState::Ready
                } else {
                    SemanticGraphReadinessState::Incomplete
                },
                generation: Some(snapshot.generation),
                total: coverage.counts.total,
                indexed: coverage.counts.indexed,
                excluded: coverage.counts.excluded,
                pending: coverage.counts.pending,
                limited: coverage.counts.limited,
                failed: coverage.counts.failed,
                stale: coverage.counts.stale,
                limitations: coverage.limitations,
                error: None,
            }
        }
        Err(error) => SemanticGraphReadiness {
            state: SemanticGraphReadinessState::Unavailable,
            generation: None,
            total: 0,
            indexed: 0,
            excluded: 0,
            pending: 0,
            limited: 0,
            failed: 0,
            stale: 0,
            limitations: vec![error.code.to_string()],
            error: Some(SemanticGraphReadinessError {
                code: error.code.to_string(),
                message: error.message,
            }),
        },
    }
}
