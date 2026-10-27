# Value-journey explainer

This package owns the static introduction on the [homepage](../../public/index.mdx) and the connected source map on the [value-journey page](../../public/explanation/value-journey.mdx).
It bundles typed source into one JSX snippet. Mintlify supplies the React hooks.
Readers need no package install or live Kast service.

From this directory:

```sh
npm ci --ignore-scripts
npm run check
npm run build
```

Run `mint dev` from `docs/public` to preview the page.
Run `mint validate` and `mint broken-links` there after a change.
Commit the generated `docs/public/snippets/value-journey.jsx` with its source.
The snippet has no runtime package imports. Styles live in `docs/public/style.css` under `.value-journey`.

The `shiki` package highlights Kotlin with Pierre's light and dark themes, as used by [Trees](https://trees.software/).
The build embeds tokens for both themes. The reader's browser loads no highlighting engine.
The page theme sets the code colors and background.
The build checks that token text preserves each source snapshot before it writes the snippet.

The source adapter imports the frozen E0, E3, and E4 Kotlin fixtures directly.
It compares each source digest with the authored oracle.
The shared `display-source.ts` adapter gives the displayed source `Signup` and `Customer` names, explicit lambda parameters, and readable line breaks.
The view admits new UTF-16 ranges and keeps both source digests. The original fixtures and route identities stay unchanged.
These checks prove that the fixture matches. They do not prove Kotlin behavior.
The route is an authored example. It has no captured compiler result or complete coverage ledger.
The gap case omits the rule for the output element. The constant case does not connect a separate value.

The tests cover cursor moves, detail levels, and path choice at a shared prefix. They also cover source anchors and negative controls.
Examine the snippet in Mintlify with a browser. Use light and dark themes and a narrow screen.
The full source opens beside the graph. On narrow screens, the pane sits above the graph and scrolls.
Use Hide source to collapse it. Reopening the pane keeps the selected connection.
Hover or focus a graph node or highlighted expression to preview its source lines. Click, Enter, or Space selects it; Escape restores the pinned highlight.
The source pane scrolls to keep the active lines in view without scrolling the page.
Wrapped code keeps its original indentation, with two extra spaces on continuation rows. Leading spaces remain in copied text but carry no highlight.
The inspector keeps the pinned explanation during a preview. Reduced-motion preferences disable transitions.

## README preview

The same build writes `docs/public/images/value-journey.svg` for the root README.
The preview uses the same source snapshots and admitted routes as the explorer.
It shows the main route and the separate-value case without scripts or motion.
The README links it to the interactive page.
Commit both generated files with their source.
