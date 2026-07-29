#[cfg(test)]
mod native_graph_tests {
    use super::*;

    fn fixture(node_count: usize, edges: &[(usize, usize, f64)]) -> NativeGraph {
        native_graph_to_csr(
            (0..node_count)
                .map(|node| NativeGraphNode {
                    database_id: Some(node as u64 + 1),
                    key: format!("n{node}"),
                })
                .collect(),
            edges
                .iter()
                .map(|&(source, target, weight)| NativeGraphEdge {
                    source,
                    target,
                    occurrence_count: 1,
                    weight,
                })
                .collect(),
        )
    }

    include!("admission_and_neighbors.rs");
    include!("algorithms.rs");
    include!("package_and_overlay.rs");
}
