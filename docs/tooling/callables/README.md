# Callable reference rendering

The canonical input remains `docs/public/reference/callables.openapi.json`.
The Kotlin generator and its parity check still own that contract.
This renderer changes documentation presentation only.

Run `python3 docs/tooling/callables/build.py` after changing the contract or viewer.
Use `--check` to reject generated drift. Documentation CI and callable parity run that check.

The generator keeps schema assertions as exact JSON text, including 64-bit integers.
References stay as graph edges; they never expand during generation.
The viewer creates deeper nodes only when readers expand a control.
Cycles and repeated references remain available without eager graph traversal.
Operation descriptions and the existing five callable URLs remain available.

The component embeds a deterministic `gzip` stream of the graph as one base64 string.
The browser decodes it before checking its exact SHA-256 and versioned shape.
This avoids both eager schema traversal and reliance on hosted data-file delivery.
Mintlify serves `.txt` files only on Enterprise plans; public source downloads use GitHub.
The checked-in `.txt` file remains the exact generated graph source for tests and review.
Corrupt data, unsupported decoding, malformed data, and cyclic pointer ancestry leave an explicit error and a complete-contract download.
Closing the page cancels pending decoding and prevents late state updates.

`pytest docs/tooling/callables` checks graph projection, pointer packing, exact facts, and source generation.
`node --test docs/tooling/callables/loader.test.mjs` checks the actual asynchronous decoder with controlled browser boundaries.
The full rendered publication runner measures all five callable pages and their declared finite expansion scope.
These checks do not prove complete accessibility or reader comprehension.
