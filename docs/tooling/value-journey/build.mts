import { readFile } from "node:fs/promises";

import dark from "@pierre/theme/pierre-dark";
import light from "@pierre/theme/pierre-light";
import { build } from "esbuild";
import { codeToTokensWithThemes } from "shiki";

// Shiki uses TextMate rules, not VS Code's separate semantic-token settings.
const themes = Object.fromEntries(
  [light, dark].map(({ name, type, colors, tokenColors }) => [
    type,
    {
      name,
      type,
      colors,
      tokenColors: [...tokenColors],
      // Preserve Pierre's string hue with 5.23:1 contrast on white.
      colorReplacements:
        type === "light" ? { "#199f43": "#147d35" } : undefined,
    },
  ]),
);

// Tokenize the frozen fixtures at build time, never in the reader's browser.
const syntax = Object.fromEntries(
  await Promise.all(
    ["E0", "E3", "E4"].map(async (variant) => {
      const source = await readFile(
        new URL(
          `../../../relation/intellij/testData/r1-value-flow/${variant}.kt`,
          import.meta.url,
        ),
        "utf8",
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
  banner: { js: "export const ValueJourney = () => {" },
  footer: { js: "return KastValueJourney.ValueJourney(); };" },
  outfile: "../../public/snippets/value-journey.jsx",
});
