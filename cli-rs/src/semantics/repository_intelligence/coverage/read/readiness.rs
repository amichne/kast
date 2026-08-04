pub(crate) fn semantic_graph_readiness_for_admission(
    admission: &runtime::SemanticWorkspaceAdmission,
) -> SemanticGraphReadiness {
    let result = runtime::semantic_workspace_read_for_admission(admission).and_then(|read| {
        let snapshot = read_coverage_from_published(
            admission.workspace_root(),
            RepositoryScope {
                language: Some("kotlin".to_string()),
                ..RepositoryScope::default()
            },
            false,
            read.published(),
        )?;
        read.revalidate()?;
        Ok(snapshot)
    });
    semantic_graph_readiness_from_result(result)
}

fn semantic_graph_readiness_from_result(
    result: Result<CoverageSnapshot>,
) -> SemanticGraphReadiness {
    match result {
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
