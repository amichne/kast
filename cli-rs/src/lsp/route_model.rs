#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct KastCustomLspRoute {
    lsp_method: &'static str,
    rpc_method: &'static str,
    inject_workspace_root: bool,
}

include!(concat!(env!("OUT_DIR"), "/lsp_custom_routes.rs"));
