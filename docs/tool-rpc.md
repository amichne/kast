# Tool RPC for agent extensions

`kast-tool-rpc` is a one-shot process interface for Kast's IDEA-backed tools.
It does not speak MCP or connect to the Codex App Server. The process uses its
current working directory to discover the exact Gradle workspace, then uses
Kast's existing IDEA preparation and semantic operation path.

The installed command is
`${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-tool-rpc-complete`.
Both adapters below call this command directly. Set `KAST_TOOL_RPC_COMMAND` to an
absolute executable path when trying a checkout build.

## Contract

`catalog` writes one UTF-8 JSON object to stdout. Its `type` is `catalog`, and
`catalog.tools` contains each tool's name, description, generated input schema,
and `READ` or `WRITE` effect. Read `catalog.schemaVersion` for the generated
public-tool contract version; the catalog also gives the call deadline and
response ceiling used by bundled adapters.

```shell
"$HOME/.local/share/kast/installation/bin/kast-tool-rpc-complete" catalog
```

`call NAME` reads one JSON object from stdin (at most 1 MiB) and writes one
JSON object to stdout. Its `type` is `complete`, `qualified`,
`rejected_document`, or `rejected`. The first three retain the canonical Kast
result under `document`; `rejected` carries a closed boundary `failure`.
Qualified results are partial evidence, and a rejected result does not prove
absence. The tool name must appear in the current catalog.

```shell
printf '%s\n' '{"request":{"type":"RUN","source":{"type":"SEARCH_DECLARATIONS","declarationName":"OrderService"}}}' |
  "${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-tool-rpc-complete" call query_symbols
```

Use the exact `ref` and qualification returned by one tool in follow-up calls.
The `add_declaration` and `replace_body` tools are marked `WRITE`; each plans,
applies, verifies, and attempts recovery within one call. Clients must request
write approval for both.

## Copilot CLI

Register the verified bundled adapter at user scope:

```shell
kast connect copilot
copilot --experimental
```

For a custom Copilot home, set `COPILOT_HOME` or pass
`kast connect copilot --destination /absolute/copilot/home`.

The extension obtains the live catalog and registers each tool with the Copilot
SDK. All tools use Copilot's normal permission policy. The extension does not
request permission to bypass tool prompts when it loads. No Copilot MCP server is needed.

## Pi

Register the bundled Pi adapter:

```shell
kast connect pi
```

The first connection respects `PI_CODING_AGENT_DIR`. You can also select its
agent home with `kast connect pi --destination /absolute/pi/agent`.
All connection commands accept `--destination` and retain the selected directory
in `~/.config/kast/config.json` for reconnect, upgrade, and disconnect. Start the
harness with the same home setting so it discovers the installed connection.

By default, connecting recovers a conflicting Kast slot from the verified release
and retains a private backup of the displaced bytes. Failed transactions restore
the prior connection and Kast configuration. Use `--no-recover` to reject a
conflict without replacement.

It registers the same live catalog in Pi.
For every catalog tool marked `WRITE`, including `add_declaration` and
`replace_body`, it asks for interactive approval and refuses the call without an
approving UI. Both clients pass their current workspace to the same RPC
command, so one Kast installation serves different repositories and worktrees.

## Client-owned query delivery

Pi and Copilot invoke `query_symbols` once at the model boundary. Their shared
portable delivery rule may then make output-only `RESUME` or retained
`READ_RESULT` calls internally. Row and evidence offsets advance independently;
an empty row page can carry evidence, and a retained prefix with zero rows
starts reading at row zero. The exact original request is submitted once.
Delivery preserves its issued result identity, original question, output and
bounded execution grant. The retained token's canonical owner checks workspace,
epoch, lifetime and scope on every physical read.

An inline reply stays unchanged. When delivery needs further calls, the adapter
returns `type: "query_delivery"`, with the unchanged `initial` RPC reply, the
ordered canonical suffix `pages`, and a `delivery` observation containing `stop`,
`rpc_count`, `request_bytes`, `response_bytes`, and the issued `result` reference when available.
`DELIVERED` means that the selected output and evidence have arrived. The
original reply still owns semantic completion and rejection. A rejection's
preview is a preview of proof, not an extra page: count its `pages` once, starting
at zero. Ordinary retained prefixes combine initial items with suffix pages.
Every qualification, omission, failure and evidence observation remains in its
own canonical reply. Retained `COMPLETION_UNPROVEN` evidence remains a tool error
even when its delivery finishes. No narrower RUN or increased semantic grant is
submitted to recover output.

One model/tool turn can therefore require three physical RPCs. These are
separate metrics. The byte fields count UTF-8 physical request and response bytes, including
response framing; they do not measure model tokens or savings. Catalog discovery is outside the invocation's
RPC count.

Delivery permits at most 64 physical invocation RPCs and 262,144 UTF-8 bytes in
its aggregate presentation, within one catalog call deadline. Reaching a limit
returns `PAGE_LIMIT`, `BYTE_LIMIT`, or `TIME_LIMIT`; a requested budget increase
returns `BUDGET_INCREASE_REQUIRED` without raising the original grant. The result reference remains
available under the existing bounded host retention lifetime. An initial reply
that alone exceeds the presentation ceiling returns a small `BYTE_LIMIT`
observation with `initial: null`, `original_outcome`, and the reference if issued.
This is an explicit delivery blocker, never a complete inline answer. No new
artifact or persistent job service is created. The raw RPC and canonical
advanced paging APIs remain available.

Cancellation stops the current child and prevents further delivery calls.
Malformed pages, changed question/result identities, and nonadvancing cursors
terminate with `MALFORMED_PAGE`, `IDENTITY_MISMATCH`, or `NON_ADVANCING`. An expired,
evicted, stale or disposed handle retains its exact canonical rejection in the
last delivered page and stops with `DELIVERY_UNAVAILABLE`. A qualified prefix with
`retention_unavailable` also stops with that blocker: its tokenless response does
not prove delivery of the lost suffix. A lost delivery reply
also stops without retry. If the initial submission response is lost, its bounded
transport error is surfaced and the client never blindly resubmits. Public query
transport currently has no cancellation/reattachment identity that proves the
remote invocation was stopped; terminating the child does not establish remote
semantic settlement. Retained data expires under its existing owner.

This portable adapter pilot does not change Codex MCP/App Server delivery or the
canonical protocol. Durable reattachment and remote cancellation settlement need
an explicit protocol decision and separate evidence. Deterministic fixtures prove
client behavior only; they do not establish native provider work, production
retention capacity, or live model usability.
