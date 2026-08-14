#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct RpcResponseTimeoutPolicy {
    ordinary: Duration,
    workspace_transition: Duration,
}

impl RpcResponseTimeoutPolicy {
    /// Proof transition: `Duration -> RpcResponseTimeoutPolicy`.
    ///
    /// Retains the configured ordinary request timeout while deriving a
    /// separate finite response allowance for complete workspace-transition
    /// dispatch. The latter covers both graph passes around the backend's
    /// one-hour maximum progress wait, then adds client-only transport and
    /// response-serialization headroom outside the server deadline.
    fn derive(ordinary: Duration) -> Self {
        let transition_dispatch = MAXIMUM_WORKSPACE_RECONCILIATION_WAIT
            .saturating_add(ordinary.saturating_mul(SEMANTIC_GRAPH_PASS_COUNT));
        Self {
            ordinary,
            workspace_transition: transition_dispatch
                .saturating_add(CLIENT_RESPONSE_COMPLETION_RESERVE),
        }
    }

    fn ordinary(self) -> Duration {
        self.ordinary
    }

    /// Boundary transition: `JSON-RPC request -> Duration`.
    ///
    /// Extracts the protocol method only to select the already-typed timeout
    /// policy consumed by the Unix socket read boundary.
    fn for_request(self, raw_request: &str) -> Result<Duration> {
        let request: Value = serde_json::from_str(raw_request)?;
        Ok(
            match RpcResponseDeadlineAuthority::derive(
                request.get("method").and_then(Value::as_str),
            ) {
                RpcResponseDeadlineAuthority::WorkspaceTransition => self.workspace_transition,
                RpcResponseDeadlineAuthority::Ordinary => self.ordinary,
            },
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RpcResponseDeadlineAuthority {
    WorkspaceTransition,
    Ordinary,
}

impl RpcResponseDeadlineAuthority {
    /// Boundary transition: `Option<&str> -> RpcResponseDeadlineAuthority`.
    ///
    /// Derives one closed timeout authority from the untrusted JSON-RPC method
    /// field. The output need not retain the method: it carries only the
    /// constrained fact consumed by exhaustive socket-read policy selection.
    fn derive(method: Option<&str>) -> Self {
        match method {
            Some(
                "raw/semantic-graph"
                | "raw/workspace-refresh"
                | "raw/apply-edits"
                | "raw/exact-file-image-cas"
                | "raw/recover-mutation-scratch",
            ) => Self::WorkspaceTransition,
            _ => Self::Ordinary,
        }
    }
}

const SEMANTIC_GRAPH_PASS_COUNT: u32 = 2;
const MAXIMUM_WORKSPACE_RECONCILIATION_WAIT: Duration = Duration::from_secs(60 * 60);
const CLIENT_RESPONSE_COMPLETION_RESERVE: Duration = Duration::from_secs(5);
