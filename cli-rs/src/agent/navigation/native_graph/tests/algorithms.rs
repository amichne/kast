    #[test]
    fn native_graph_tarjan_condensation_and_components_are_deterministic() {
        let graph = fixture(
            6,
            &[
                (0, 1, 1.0),
                (1, 0, 1.0),
                (1, 2, 1.0),
                (2, 3, 1.0),
                (3, 2, 1.0),
                (4, 5, 1.0),
            ],
        );
        assert_eq!(native_connected_components(&graph), vec![0, 0, 0, 0, 1, 1]);
        let first = native_tarjan_scc(&graph);
        assert_eq!(first, native_tarjan_scc(&graph));
        assert_eq!(
            native_condensation_topological_order(&graph, &first),
            native_condensation_topological_order(&graph, &first)
        );
    }

    #[test]
    fn native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth() {
        const CHILD_ENV: &str = "KAST_NATIVE_GRAPH_DEEP_TARJAN_CHILD";
        if std::env::var_os(CHILD_ENV).is_some() {
            let node_count = 50_000;
            let edges = (0..node_count - 1)
                .map(|node| (node, node + 1, 1.0))
                .collect::<Vec<_>>();
            let membership = native_tarjan_scc(&fixture(node_count, &edges));
            assert_eq!(membership.len(), node_count);
            return;
        }

        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "agent::native_graph_tests::native_graph_tarjan_handles_deep_acyclic_chain_without_process_stack_growth",
            ])
            .env(CHILD_ENV, "1")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "deep Tarjan child failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    #[test]
    fn native_graph_weighted_leiden_is_deterministic_and_keeps_refined_communities_connected() {
        let graph = fixture(
            6,
            &[
                (0, 1, 10.0),
                (1, 2, 10.0),
                (2, 0, 10.0),
                (3, 4, 10.0),
                (4, 5, 10.0),
                (5, 3, 10.0),
                (2, 3, 0.1),
            ],
        );
        let first = native_weighted_leiden(&graph, 1.0);
        assert_eq!(first, native_weighted_leiden(&graph, 1.0));
        assert_eq!(first[0], first[1]);
        assert_eq!(first[1], first[2]);
        assert_eq!(first[3], first[4]);
        assert_eq!(first[4], first[5]);
        assert_ne!(first[2], first[3]);
    }

    #[test]
    fn native_graph_preserves_parallel_typed_edge_occurrence_weight() {
        let graph = fixture(2, &[(0, 1, 2.0), (0, 1, 3.0)]);
        assert_eq!(graph.edges.len(), 2);
        assert_eq!(graph.edges.iter().map(|edge| edge.occurrence_count).sum::<usize>(), 2);
        assert_eq!(graph.offsets, vec![0, 1, 1]);
        assert_eq!(graph.targets, vec![1]);
        assert_eq!(graph.edges.iter().map(|edge| edge.weight).sum::<f64>(), 5.0);
    }
