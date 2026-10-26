# Value-journey explainer

This package owns the interactive example on the [value-journey page](../../public/explanation/value-journey.mdx).
It bundles typed source into one JSX snippet for Mintlify's injected React hooks.
Readers need no package install, IDE connection, or running Kast service.

From this directory:

```sh
npm ci --ignore-scripts
npm run check
npm run build
```

Run `mint dev` from `docs/public` to preview the actual documentation page.
Run `mint validate` and `mint broken-links` there after a change.
Commit the generated `docs/public/snippets/value-journey.jsx` with its source.
The snippet has no runtime package imports. Styles live in `docs/public/style.css` under `.value-journey`.

The `shiki` package highlights Kotlin with Pierre's light and dark themes, as used by [Trees](https://trees.software/).
The build embeds tokens for both themes. The reader's browser loads no highlighting engine.
The page theme controls syntax colors and the surrounding editor surface.
The build checks that token text preserves each source snapshot before it writes the snippet.

The source adapter imports the frozen E0, E3, and E4 Kotlin fixtures directly.
It checks each source digest against the authored oracle and admits exact UTF-16 ranges before rendering.
These checks prove fixture consistency, not Kotlin semantics.
The expected route remains an authored example, never a captured compiler result or a complete coverage ledger.
The gap case deliberately omits the reviewed output-element transfer. The constant case does not connect an unrelated value.

The tests cover cursor navigation, detail levels, shared-prefix path selection, source anchors, and the negative controls.
Browser checks must also exercise the generated snippet in Mintlify, including light and dark themes and a narrow screen.
