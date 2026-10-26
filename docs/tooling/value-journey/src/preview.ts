import { loadExamples } from "./trace";

const xml = (value: string): string =>
  value.replace(/[&<>"']/g, (character) => {
    switch (character) {
      case "&":
        return "&amp;";
      case "<":
        return "&lt;";
      case ">":
        return "&gt;";
      case '"':
        return "&quot;";
      default:
        return "&apos;";
    }
  });

/** A static view of the same admitted examples as the interactive component. */
export async function buildPreview(): Promise<string> {
  const admitted = await loadExamples();
  if (admitted.kind === "rejected") throw new Error(admitted.code);
  const example = admitted.examples.find((candidate) => candidate.id === "e0");
  const constant = admitted.examples.find(
    (candidate) => candidate.id === "constant",
  );
  if (!example || !constant) throw new Error("Missing preview example");
  const path = example.paths[0];
  const steps = path.steps.filter((step) => step.importance === "landmark");
  const changedLine = constant.source
    .split("\n")
    .find((line) => line.startsWith("internal fun convert("));
  if (!changedLine) throw new Error("Missing comparison source");
  const changedExpression = changedLine.slice(changedLine.indexOf("items.map"));
  const rows = steps
    .map((step, index) => {
      const y = 206 + index * 78;
      const line = example.source
        .slice(0, step.anchor.start)
        .split("\n").length;
      const library = step.authority === "reviewed-library";
      return `<g>
      <rect class="source-row" x="40" y="${y}" width="588" height="62" rx="10"/>
      <text class="line" x="58" y="${y + 26}">${line}</text>
      <text class="code" x="92" y="${y + 27}">${xml(step.anchor.text)}</text>
      <text class="sub" x="92" y="${y + 48}">${xml(step.title)}</text>
      <path class="source-link" d="M640 ${y + 31}H676" marker-end="url(#arrow)"/>
      ${index < steps.length - 1 ? `<path class="route-link" d="M708 ${y + 54}V${y + 98}" marker-end="url(#arrow)"/>` : ""}
      <rect class="route-node${library ? " library" : ""}" x="680" y="${y}" width="478" height="62" rx="10"/>
      <circle class="value-dot" cx="708" cy="${y + 31}" r="7"/>
      <text class="node-title" x="730" y="${y + 25}">${xml(step.title)}</text>
      <text class="node-value" x="730" y="${y + 47}">${xml(step.value)}</text>
    </g>`;
    })
    .join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="830" viewBox="0 0 1200 830" role="img" aria-labelledby="title description">
  <title id="title">Kast: follow a value through Kotlin</title>
  <desc id="description">A Pet moves through a helper, a PetListing pet field, a callback, and a possible list element. A reviewed map rule supplies the list connection. A second example creates a separate constant and has no route from the original value to that list. This is an illustration from fixed source, not a live Kast result.</desc>
  <style>
    text { font-family:SUSE,system-ui,-apple-system,BlinkMacSystemFont,sans-serif; fill:#171816; }
    .paper { fill:#fbfbf9; stroke:#dfe3dc; }
    .panel { fill:#fff; stroke:#dfe3dc; }
    .eyebrow { font-size:16px; font-weight:600; fill:#626660; letter-spacing:1px; }
    .heading { font-size:36px; font-weight:700; }
    .intro { font-size:20px; fill:#626660; }
    .source-row { fill:#f1f4ee; }
    .code { font-family:Menlo,Consolas,monospace; font-size:17px; fill:#176754; }
    .line { font-family:Menlo,Consolas,monospace; font-size:14px; fill:#626660; }
    .sub { font-size:14px; fill:#626660; }
    .source-link { stroke:#626660; stroke-width:1.5; stroke-dasharray:3 4; fill:none; }
    .route-link { stroke:#176754; stroke-width:2; fill:none; }
    .route-node { fill:#edf7f0; stroke:#176754; }
    .library { stroke:#895213; stroke-dasharray:4 3; }
    .value-dot { fill:#176754; }
    .node-title { font-size:20px; font-weight:600; }
    .node-value { font-family:Menlo,Consolas,monospace; font-size:14px; fill:#176754; }
    .comparison { fill:#fff5e5; stroke:#895213; }
    .comparison-title { font-size:22px; font-weight:650; }
    .comparison-code { font-family:Menlo,Consolas,monospace; font-size:16px; fill:#895213; }
    .stop { fill:#895213; font-size:18px; }
    .footer { font-size:16px; fill:#626660; }
    .cta { font-size:18px; fill:#3154c7; font-weight:600; }
    @media(prefers-color-scheme:dark) {
      text { fill:#f4f6f0; }
      .paper { fill:#171816; stroke:#42483e; }
      .panel { fill:#20221f; stroke:#42483e; }
      .eyebrow, .intro, .line, .sub, .footer { fill:#b5bcb0; }
      .source-row { fill:#292e26; }
      .code, .node-value, .value-dot { fill:#8cd2b6; }
      .source-link { stroke:#b5bcb0; }
      .route-link { stroke:#8cd2b6; }
      .route-node { fill:#243a30; stroke:#8cd2b6; }
      .library { stroke:#edbc79; }
      .comparison { fill:#382f22; stroke:#edbc79; }
      .comparison-code, .stop { fill:#edbc79; }
      .cta { fill:#a6c9ff; }
    }
  </style>
  <defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z" fill="#789282"/></marker></defs>
  <rect class="paper" x="1" y="1" width="1198" height="828" rx="22"/>
  <text class="eyebrow" x="40" y="43">Kast / Follow the value</text>
  <text class="heading" x="40" y="94">Where does this value go?</text>
  <text class="intro" x="40" y="130">Follow it through a helper, a field, and a list.</text>
  <text class="eyebrow" x="40" y="183">SOURCE / EXACT EXPRESSIONS</text>
  <text class="eyebrow" x="680" y="183">THE SAME VALUE / EACH CONNECTION</text>
  ${rows}
  <text class="sub" x="680" y="613">Dashed outline: the list connection needs a reviewed map rule.</text>
  <rect class="comparison" x="40" y="644" width="1118" height="102" rx="12"/>
  <text class="comparison-title" x="60" y="676">Return a different value. The route changes.</text>
  <text class="comparison-code" x="60" y="710">${xml(changedExpression)}</text>
  <path d="M770 704H814M814 694V714" stroke="#895213" stroke-width="2"/>
  <text class="stop" x="836" y="704">The original does not</text>
  <text class="stop" x="836" y="728">reach this list.</text>
  <text class="footer" x="40" y="792">Illustration from fixed Kotlin source. No live query.</text>
  <text class="cta" x="840" y="792">Try the interactive example →</text>
</svg>\n`.replace(/[ \t]+$/gm, "");
}
