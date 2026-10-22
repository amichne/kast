# Landing demo

The [54-second replay](kast-demo.mp4) follows one response value through a small public Kotlin fixture.
The [inline animation](kast-demo.svg), [still preview](kast-demo.png), and [transcript](transcript.txt) show the same results.

## Evidence and limits

This clip is a presentation projection of earlier verified Tool RPC output.
It replays results from the published Kast Control/Host pair `v0.50.2`.
It uses source revision `f1ad6f4ce7b13be6a574183b2ba505149d92b581`, macOS arm64, and IDEA `2026.2.3`.
The project was already imported and prepared.
No new IDE, build, semantic query, or source change was needed to make this replay.

Check the results against the [public V0 source](../../relation/intellij/testData/request-response/src/main/kotlin/fixture/variants/v0/V0.kt).
Its SHA-256 is `75defdcdc8cb7cca72dd1991f5797ab71d8deb35acd76316813b165b5fec57a6`.
The existing release audit checked 112 conditions with no mismatches.
This presentation reuses that audit; it is not a new independent acceptance run.

The incoming investigation completes.
Its retained path has 13 compiler transfers, ending at `FeatureResponse.link` with `SUPPORTED_DOMAIN_END` and conserved accounting.
The outgoing investigation remains `COMPLETION_UNPROVEN` at an `UNMODELED_CALL` to `Transport.send`.
Its default retained read returns `RESULT_TOO_LARGE`.
An explicit `maxReturnedBytes: 524288` reads the same retained evidence with 11 transfers.
That presentation allowance does not discharge its `NATIVE_FLOW` obligation or change semantic completion.

The clip groups some field transfers to make them easy to read.
[evidence.json](evidence.json) keeps all transfer kinds and source ranges for the seven shown calls.
It also keeps result states, measured call times, and input hashes.
It holds only the fields chosen for public display.
Raw captures, opaque references, host identities, and private paths stay outside this repository.

Call excerpts are reordered to explain the flow.
Each wait uses the measured time from process start to the first complete reply on stdout.
The video rounds that time to a frame at 20 fps.
Reading pauses are added; setup, reference acquisition for the transport, and witness drainage are omitted.
The clip is not a cold-start or end-to-end benchmark.
These are static compiler results, with no claim that the fixture ran, that all M1 routes work, or that enterprise acceptance passed.

## Reproduce the presentation

Use Node 24 or later, `rsvg-convert`, and `ffmpeg` with `libx264` already installed.
From the repository root:

```shell
node docs/demo/render.mjs
```

The renderer uses the checked-in evidence file.
It creates the SVG, PNG, MP4, and transcript, then removes only its own temporary frame directory.
It makes no network or Kast calls.

To project an existing matching capture again:

```shell
node docs/demo/project.mjs /path/to/private/capture-root /path/to/private/audit.json
node docs/demo/render.mjs
```

The projector checks the release, source hash, audit, and recorded result states.
It checks that each seed uses the exact refs issued for its callable and enclosing function.
It rejects a mismatch before writing the evidence file.
It exports a fixed set of public fields.

For a fresh semantic capture, use an isolated owned IDEA profile and imported fixture.
The [request templates](requests.json) include the transport lookup omitted from the clip.
They use contract 17's required EMIT terminals, with output inside `terminalStep`.
This syntax update does not change the historical clip or evidence and does not establish new semantic results.
Replace each placeholder with the ref or result just issued by its prior call.
Follow the [public request capture guide](../../experiments/representation-impact/README.md) and [Tool RPC contract](../tool-rpc.md).
Acquire fresh callable references through public queries; keep each `RUN` and its retained reads tied to the same result.
Never reuse or decode old opaque references.
Preserve rejection states and drain the evidence before making an absence claim.

## Hosting

The README embeds a repository-hosted SVG, with a linked MP4 and still fallback.
The animation respects the browser's reduced-motion preference by showing the final summary.
The MP4 provides pause and seek controls in a video player.

The [reference presentation](https://github.com/Graphify-Labs/graphify#see-it-in-action) currently embeds `docs/demo-path.svg` through `raw.githubusercontent.com`.
Its 900×360 SVG uses a 10-second repeating animation.
It inspired a compact, readable result sequence; no asset or product claim was copied.

The GitHub connector and CLI expose no native README video-attachment upload operation.
No attachment URL was invented.
The checked-in SVG and MP4 work without an account, new hosting service, or paid infrastructure.
