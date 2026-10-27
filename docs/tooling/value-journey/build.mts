import { readFile, writeFile } from "node:fs/promises";

import dark from "@pierre/theme/pierre-dark";
import light from "@pierre/theme/pierre-light";
import { build } from "esbuild";
import { codeToTokensWithThemes } from "shiki";
import type { ThemeRegistration } from "shiki";
import { displaySource } from "./src/display-source.ts";

// Shiki uses TextMate rules, not VS Code's separate semantic-token settings.
const themes = Object.fromEntries(
  [light, dark].map(
    ({ name, type, colors, tokenColors }): [string, ThemeRegistration] => [
      type,
      {
        name,
        type,
        colors,
        tokenColors: [...tokenColors],
        // Keep Pierre's hues readable on both the normal and selected surfaces.
        colorReplacements:
          type === "light"
            ? {
                "#199f43": "#147d35",
                "#d32a61": "#c12459",
                "#08c0ef": "#006579",
              }
            : {
                "#ff678d": "#ff7c9d",
                "#d568ea": "#de8aef",
                "#9d6afb": "#bb99ff",
              },
      },
    ],
  ),
);

// Tokenize the frozen fixtures at build time, never in the reader's browser.
const syntax = Object.fromEntries(
  await Promise.all(
    (["E0", "E3", "E4"] as const).map(async (variant) => {
      const source = displaySource(
        await readFile(
          new URL(
            `../../../relation/intellij/testData/r1-value-flow/${variant}.kt`,
            import.meta.url,
          ),
          "utf8",
        ),
        variant,
      );
      const lines = await codeToTokensWithThemes(source, {
        lang: "kotlin",
        themes,
      });
      if (
        lines
          .map((line) => line.map((token) => token.content).join(""))
          .join("\n") !== source
      )
        throw new Error(`Shiki changed the ${variant} source snapshot`);
      return [
        variant,
        lines.map((line) =>
          line.map((token) => ({
            text: token.content,
            start: token.offset,
            light:
              token.variants.light?.color ?? light.colors["editor.foreground"],
            dark:
              token.variants.dark?.color ?? dark.colors["editor.foreground"],
          })),
        ),
      ];
    }),
  ),
);

await build({
  entryPoints: ["src/ValueJourney.tsx"],
  bundle: true,
  format: "iife",
  globalName: "KastValueJourney",
  jsx: "preserve",
  loader: { ".kt": "text" },
  define: { KAST_SYNTAX: JSON.stringify(syntax) },
  // Mintlify retains only exported component bodies, not module-local helpers.
  banner: { js: "export const ValueJourney = (props) => {" },
  footer: { js: "return KastValueJourney.ValueJourney(props); };" },
  outfile: "../../public/snippets/value-journey.jsx",
});

// Build the README preview from the same admitted routes and source snapshots.
const previewModule = await build({
  entryPoints: ["src/preview.ts"],
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
  loader: { ".kt": "text" },
});
const previewCode = previewModule.outputFiles[0];
if (!previewCode) throw new Error("Missing preview bundle");
const { buildPreview } = (await import(
  `data:text/javascript;base64,${Buffer.from(previewCode.contents).toString("base64")}`
)) as typeof import("./src/preview");
await writeFile(
  new URL("../../public/images/value-journey.svg", import.meta.url),
  await buildPreview(),
);
