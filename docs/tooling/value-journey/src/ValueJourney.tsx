import type { JSX } from "react";

import { AnalysisCursor, loadExamples } from "./trace";
import type { Detail, Example, ExampleId, Step } from "./trace";

type Walkthrough =
  | { readonly kind: "loading" }
  | {
      readonly kind: "rejected";
      readonly code: "SOURCE_CHANGED" | "INVALID_ANCHOR" | "LOAD_FAILED";
    }
  | {
      readonly kind: "ready";
      readonly examples: readonly [Example, ...Example[]];
      readonly cursor: AnalysisCursor;
    };

/** Docs-only explainer. No IDE connection, semantic query, editing, or execution. */
export function ValueJourney(): JSX.Element {
  const [walkthrough, setWalkthrough] = useState<Walkthrough>({
    kind: "loading",
  });
  const [detail, setDetail] = useState<Detail>("story");
  const [portal, setPortal] = useState(false);
  const [proof, setProof] = useState(false);

  useEffect(() => {
    let disposed = false;
    async function load() {
      try {
        const result = await loadExamples();
        if (disposed) return;
        setWalkthrough(
          result.kind === "ready"
            ? { ...result, cursor: AnalysisCursor.start(result.examples[0]) }
            : result,
        );
      } catch {
        console.error("Kast value journey: snapshot loading failed");
        if (!disposed)
          setWalkthrough({ kind: "rejected", code: "LOAD_FAILED" });
      }
    }
    void load();
    return () => {
      disposed = true;
    };
  }, []);

  if (walkthrough.kind === "loading")
    return (
      <div className="value-journey vj-loading not-prose">
        The example loads here…
      </div>
    );
  if (walkthrough.kind === "rejected")
    return (
      <div className="value-journey vj-loading not-prose" role="alert">
        The example is unavailable ({walkthrough.code}).
      </div>
    );

  const { cursor, examples } = walkthrough;
  const { example, path, step } = cursor;
  const visible = cursor.visible(detail);
  const line = example.source.slice(0, step.anchor.start).split("\n").length;
  const stopped = cursor.atEnd && path.ending.kind === "unresolved";
  const authority =
    step.authority === "compiler" ? "◆ Compiler" : "◇ Reviewed library";
  const lines = example.source.trimEnd().split("\n");

  function navigate(next: AnalysisCursor) {
    setWalkthrough({ kind: "ready", examples, cursor: next });
    setPortal(false);
    setProof(false);
  }

  function chooseExample(id: ExampleId, compare = false) {
    const target = examples.find((candidate) => candidate.id === id);
    if (!target) return;
    let next = AnalysisCursor.start(target);
    if (compare) {
      for (const candidate of next.path.steps) {
        if (
          path.steps.some(
            (old, index) => index <= cursor.index && old.id === candidate.id,
          )
        )
          next = next.seek(candidate);
      }
    }
    navigate(next);
  }

  function sourceLine(index: number, highlight = true) {
    return KAST_SYNTAX[example.variant][index]?.map((token) => {
      const from = Math.max(0, step.anchor.start - token.start);
      const to = Math.min(token.text.length, step.anchor.end - token.start);
      return (
        <span
          key={token.start}
          style={{ color: `light-dark(${token.light}, ${token.dark})` }}
        >
          {highlight && from < to ? (
            <>
              {token.text.slice(0, from)}
              <mark>{token.text.slice(from, to)}</mark>
              {token.text.slice(to)}
            </>
          ) : (
            token.text
          )}
        </span>
      );
    });
  }

  function progressLabel(candidate: Step): string {
    return `Step ${path.steps.indexOf(candidate) + 1}: ${candidate.title}`;
  }

  return (
    <div
      className="value-journey not-prose"
      data-example={example.id}
      data-step={step.id}
    >
      <div className="vj-heading">
        <h2>Follow one value.</h2>
        <span className="vj-mode">Interactive illustration</span>
      </div>

      <div className="vj-options">
        <label>
          Explore
          <select
            value={example.id}
            onChange={(event) => {
              const target = examples.find(
                (candidate) => candidate.id === event.target.value,
              );
              if (target) chooseExample(target.id);
            }}
          >
            {examples.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.label}
              </option>
            ))}
          </select>
        </label>
        <div className="vj-detail" role="group" aria-label="Explanation detail">
          {(["story", "normal", "proof"] as const).map((level) => (
            <button
              key={
                { story: "Overview", normal: "Every step", proof: "Evidence" }[
                  level
                ]
              }
              type="button"
              aria-pressed={detail === level}
              onClick={() => setDetail(level)}
            >
              {
                { story: "Overview", normal: "Every step", proof: "Evidence" }[
                  level
                ]
              }
            </button>
          ))}
        </div>
      </div>

      <p className="vj-question">{example.description}</p>
      <nav className="vj-route" aria-label="Value route">
        {cursor.visible("story").map((candidate) => (
          <button
            key={candidate.id}
            type="button"
            aria-current={candidate === step ? "step" : undefined}
            data-reached={path.steps.indexOf(candidate) <= cursor.index}
            onClick={() => navigate(cursor.seek(candidate))}
          >
            <span aria-hidden="true">●</span>
            <span>{candidate.title}</span>
          </button>
        ))}
      </nav>
      <div className="vj-file">
        <span>
          <span aria-hidden="true">Kt</span> {example.variant}.kt
        </span>
        <span>Kotlin example</span>
      </div>

      <section
        className="vj-source"
        aria-label="Kotlin source with inline explanation"
      >
        {lines.map((text, index) => {
          return (
            <div key={index}>
              <div
                className={
                  index + 1 === line ? "vj-line vj-line-active" : "vj-line"
                }
              >
                <span className="vj-line-number" aria-hidden="true">
                  {index + 1}
                </span>
                <code>{text ? sourceLine(index) : "\u00a0"}</code>
              </div>
              {index + 1 === line && (
                <div className={stopped ? "vj-inline vj-gap" : "vj-inline"}>
                  <div className="vj-value">
                    <span aria-hidden="true">●</span> {step.value}
                    <span className="vj-value-label">tracked value</span>
                  </div>
                  <div className="vj-explanation">
                    {stopped && (
                      <span className="vj-eyebrow">Missing library rule</span>
                    )}
                    <div className="vj-step-heading">
                      <h3>
                        {stopped ? "The explanation stops here." : step.title}
                      </h3>
                      <span className={`vj-authority vj-${step.authority}`}>
                        {authority} · illustration
                      </span>
                    </div>
                    <p>{step.explanation}</p>

                    {step.context.kind === "call" && (
                      <>
                        <button
                          className="vj-disclosure"
                          type="button"
                          aria-expanded={portal}
                          onClick={() => setPortal(!portal)}
                        >
                          {portal ? "−" : "+"} Peek {step.context.title}
                        </button>
                        {portal && (
                          <div className="vj-portal">
                            <span className="vj-eyebrow">
                              Originating function · same source snapshot
                            </span>
                            <pre>
                              <code>
                                {sourceLine(
                                  step.context.anchor.source
                                    .slice(0, step.context.anchor.start)
                                    .split("\n").length - 1,
                                  false,
                                )}
                              </code>
                            </pre>
                            <p>
                              Return → exact call on line {line}. The call site
                              stays in view.
                            </p>
                          </div>
                        )}
                      </>
                    )}

                    {step.context.kind === "callback" &&
                      (proof || detail === "proof") && (
                        <div className="vj-callback">
                          <div>
                            <span className="vj-eyebrow">Callback frame</span>
                            <code>map.transform</code>
                          </div>
                          {(proof || detail === "proof") && (
                            <dl>
                              <dt>Supplier</dt>
                              <dd>convert</dd>
                              <dt>Anonymous owner</dt>
                              <dd>lambda on line 9</dd>
                              <dt>Result carries</dt>
                              <dd>
                                <code>
                                  {example.variant === "E3"
                                    ? "Pet"
                                    : "PetListing.pet"}
                                </code>
                              </dd>
                            </dl>
                          )}
                          <div className="vj-proof-split">
                            <span>◆ Bind lambda to transform</span>
                            <span>Compiler · expected</span>
                          </div>
                          <div
                            className={
                              stopped
                                ? "vj-proof-split vj-missing"
                                : "vj-proof-split"
                            }
                          >
                            <span>◇ Callback result → output element</span>
                            <span>
                              {stopped
                                ? "Missing proof"
                                : step.authority === "reviewed-library"
                                  ? "Reviewed rule · expected"
                                  : "Next expected transfer"}
                            </span>
                          </div>
                        </div>
                      )}

                    {cursor.atEnd && (
                      <div className="vj-ending">
                        <strong>
                          {path.ending.kind === "unresolved"
                            ? "The library connection needs evidence"
                            : path.ending.kind === "excluded"
                              ? "The list gets a different value"
                              : "This route ends here"}
                        </strong>
                        <p>{path.ending.explanation}</p>
                      </div>
                    )}

                    {example.paths.length > 1 && cursor.index <= 1 && (
                      <div className="vj-fork">
                        <span className="vj-eyebrow">Choose a route</span>
                        {example.paths.map((alternative) => (
                          <button
                            type="button"
                            key={alternative.id}
                            aria-pressed={path === alternative}
                            onClick={() => navigate(cursor.select(alternative))}
                          >
                            <span aria-hidden="true">↳</span>{" "}
                            {alternative.label}
                          </button>
                        ))}
                        <p>These routes describe possible uses in source.</p>
                      </div>
                    )}

                    {detail !== "proof" && (
                      <button
                        className="vj-disclosure"
                        type="button"
                        aria-expanded={proof}
                        onClick={() => setProof(!proof)}
                      >
                        {proof ? "−" : "+"} Show the evidence
                      </button>
                    )}
                    {(proof || detail === "proof") && (
                      <dl className="vj-proof-details">
                        <dt>Operation</dt>
                        <dd>
                          <code>{step.operation}</code>
                        </dd>
                        <dt>Source anchor</dt>
                        <dd>
                          UTF-16 [{step.anchor.start}, {step.anchor.end})
                        </dd>
                        <dt>Exact expression</dt>
                        <dd>
                          <code>{step.anchor.text}</code>
                        </dd>
                        <dt>Evidence origin</dt>
                        <dd>
                          Authored expected oracle, not a captured Kast response
                        </dd>
                        <dt>Compiler identity</dt>
                        <dd>Not captured</dd>
                        <dt>Coverage ledger</dt>
                        <dd>Not captured</dd>
                        <dt>Displayed source SHA-256</dt>
                        <dd>
                          <code>{example.sha256}</code>
                        </dd>
                        <dt>Original fixture SHA-256</dt>
                        <dd>
                          <code>{example.fixtureSha256}</code>
                        </dd>
                      </dl>
                    )}

                    {(stopped ||
                      (example.id === "e0" &&
                        (cursor.atEnd ||
                          step.authority === "reviewed-library"))) && (
                      <button
                        type="button"
                        className="vj-compare"
                        onClick={() =>
                          chooseExample(stopped ? "e0" : "gap", true)
                        }
                      >
                        {stopped
                          ? "Restore the reviewed rule"
                          : "What if the library rule is missing?"}{" "}
                        <span aria-hidden="true">↗</span>
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </section>

      <div className="vj-controls">
        <div className="vj-position" role="status">
          <span className="vj-eyebrow">Step</span>
          {cursor.index + 1} / {path.steps.length}
        </div>
        <nav className="vj-progress" aria-label="Value journey steps">
          {path.steps
            .filter(
              (candidate) => candidate === step || visible.includes(candidate),
            )
            .map((candidate) => (
              <button
                type="button"
                key={candidate.id}
                aria-label={progressLabel(candidate)}
                aria-current={candidate === step ? "step" : undefined}
                title={progressLabel(candidate)}
                onClick={() => navigate(cursor.seek(candidate))}
              >
                {String(path.steps.indexOf(candidate) + 1).padStart(2, "0")}
              </button>
            ))}
        </nav>
        <div className="vj-buttons">
          <button
            type="button"
            disabled={cursor.index === 0}
            onClick={() => navigate(cursor.move("previous", detail))}
          >
            <span aria-hidden="true">←</span> Previous
          </button>
          <button
            type="button"
            className="vj-next"
            disabled={cursor.atEnd}
            onClick={() => navigate(cursor.move("next", detail))}
          >
            {cursor.index === 0 ? "Follow the value" : "Next step"}{" "}
            <span aria-hidden="true">→</span>
          </button>
        </div>
      </div>
      <div className="vj-limits">
        <span aria-hidden="true">ⓘ</span>
        <p>
          Illustration from fixed source. This page does not run Kast or your
          code.
        </p>
      </div>
    </div>
  );
}
