import type { JSX, KeyboardEvent as ReactKeyboardEvent } from "react";

import { AnalysisCursor, loadExamples } from "./trace";
import type { Example, ExampleId, SourceAnchor, Step } from "./trace";

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

type Focus =
  | { readonly kind: "pinned" }
  | { readonly kind: "preview"; readonly step: Step };

/** Docs-only illustration: no IDE connection, semantic query, or execution. */
export function ValueJourney({
  preview = false,
}: { readonly preview?: boolean } = {}): JSX.Element {
  const [walkthrough, setWalkthrough] = useState<Walkthrough>({
    kind: "loading",
  });
  const [focus, setFocus] = useState<Focus>({ kind: "pinned" });
  const [sourceVisible, setSourceVisible] = useState(true);
  const [sourcePane, setSourcePane] = useState<HTMLDivElement | null>(null);
  const [canvas, setCanvas] = useState<HTMLDivElement | null>(null);
  const [wires, setWires] = useState({ call: "", batch: "", output: "" });

  useEffect(() => {
    let disposed = false;
    async function load() {
      try {
        const result = await loadExamples();
        if (!disposed)
          setWalkthrough(
            result.kind === "ready"
              ? { ...result, cursor: AnalysisCursor.start(result.examples[0]) }
              : result,
          );
      } catch {
        console.error("Kast source map: snapshot admission failed");
        if (!disposed)
          setWalkthrough({ kind: "rejected", code: "LOAD_FAILED" });
      }
    }
    void load();
    return () => {
      disposed = true;
    };
  }, []);

  useEffect(() => {
    if (focus.kind !== "preview") return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === "Escape") setFocus({ kind: "pinned" });
    };
    document.addEventListener("keydown", dismiss);
    return () => document.removeEventListener("keydown", dismiss);
  }, [focus.kind]);

  useEffect(() => {
    if (!sourcePane || !sourceVisible) return;
    function reveal() {
      if (!sourcePane) return;
      const active = sourcePane.querySelector<HTMLElement>(
        '.vj-source-line[data-active="true"]',
      );
      if (!active) return;
      const pane = sourcePane.getBoundingClientRect();
      const line = active.getBoundingClientRect();
      if (line.top < pane.top || line.bottom > pane.bottom)
        sourcePane.scrollTop += line.top - pane.top - 24;
    }
    reveal();
    const observer = new ResizeObserver(reveal);
    observer.observe(sourcePane);
    return () => observer.disconnect();
  }, [sourcePane, sourceVisible, walkthrough, focus]);

  const exampleId =
    walkthrough.kind === "ready" ? walkthrough.cursor.example.id : "loading";
  const routeId =
    walkthrough.kind === "ready" ? walkthrough.cursor.path.id : "loading";
  useEffect(() => {
    if (!canvas) return;
    const producer = canvas.querySelector<HTMLElement>(
      '[data-vj-node="producer"]',
    );
    const draft = canvas.querySelector<HTMLElement>('[data-vj-node="draft"]');
    const batch = canvas.querySelector<HTMLElement>('[data-vj-node="batch"]');
    const output = canvas.querySelector<HTMLElement>('[data-vj-node="output"]');
    if (!producer || !draft || !batch || !output) return;
    function draw() {
      if (!canvas || !producer || !draft || !batch || !output) return;
      const root = canvas.getBoundingClientRect();
      const a = producer.getBoundingClientRect();
      const b = draft.getBoundingClientRect();
      const c = batch.getBoundingClientRect();
      const d = output.getBoundingClientRect();
      const x1 = a.right - root.left;
      const y1 = a.top + a.height / 2 - root.top;
      const x2 = b.left - root.left;
      const y2 = b.top + b.height / 2 - root.top;
      const direct = exampleId === "fork";
      const fromX =
        (direct ? a.left + a.width / 2 : b.left + b.width / 2) - root.left;
      const fromY = (direct ? a.bottom : b.bottom) - root.top;
      const toX = c.left + c.width / 2 - root.left;
      const toY = c.top - root.top;
      const containerSource = direct && routeId === "wrapper" ? b : c;
      const right = d.left > containerSource.right;
      const outputFromX =
        (right
          ? containerSource.right
          : containerSource.left + containerSource.width / 2) - root.left;
      const outputFromY =
        (right
          ? containerSource.top + containerSource.height / 2
          : containerSource.bottom) - root.top;
      const outputToX = (right ? d.left : d.left + d.width / 2) - root.left;
      const outputToY = (right ? d.top + d.height / 2 : d.top) - root.top;
      setWires({
        call: `M${x1},${y1} C${x1 + 28},${y1} ${x2 - 28},${y2} ${x2},${y2}`,
        batch: `M${fromX},${fromY} C${fromX},${fromY + 20} ${toX},${toY - 20} ${toX},${toY}`,
        output: right
          ? `M${outputFromX},${outputFromY} C${outputFromX + 28},${outputFromY} ${outputToX - 28},${outputToY} ${outputToX},${outputToY}`
          : `M${outputFromX},${outputFromY} C${outputFromX},${outputFromY + 20} ${outputToX},${outputToY - 20} ${outputToX},${outputToY}`,
      });
    }
    draw();
    const observer = new ResizeObserver(draw);
    for (const node of [canvas, producer, draft, batch, output])
      observer.observe(node);
    return () => observer.disconnect();
  }, [canvas, exampleId, routeId, sourceVisible]);

  if (walkthrough.kind === "loading")
    return (
      <div className="value-journey vj-loading not-prose" role="status">
        Loading the source example…
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
  // Compress the reader's navigation, not the underlying evidence path.
  const frames = path.steps.filter(
    (candidate) =>
      candidate.operation === "PRODUCER" ||
      candidate.id === "toClientValue.call.result" ||
      candidate.operation === "CONSTRUCT_FIELD" ||
      candidate.operation === "CALLBACK_RETURN" ||
      candidate.authority === "reviewed-library",
  );
  const position = Math.max(
    0,
    frames.findLastIndex(
      (candidate) => path.steps.indexOf(candidate) <= cursor.index,
    ),
  );
  const focused = focus.kind === "preview" ? focus.step : step;
  const lineOf = (anchor: SourceAnchor) =>
    example.source.slice(0, anchor.start).split("\n").length - 1;
  const origin = path.steps[0];
  // These are presentation slices of the admitted display snapshot, not semantic searches.
  const producerStart = lineOf(origin.anchor) - 1;
  const draftStart = producerStart + 2;
  const batchStart = draftStart + 2;
  const lines = example.source.trimEnd().split("\n");
  const fork = example.id === "fork";
  const different = example.id === "constant";
  const gap = example.id === "gap";
  const atEnd = position === frames.length - 1;
  const stopped = atEnd && path.ending.kind === "unresolved";
  const onEmailRoute = fork && path.id === "wrapper";
  const draftFrame = frames.find(
    (candidate) => candidate.operation === "CONSTRUCT_FIELD",
  );
  const batchFrame = frames.find(
    (candidate) => candidate.operation === "CALLBACK_RETURN",
  );
  const outputFrame =
    gap || different
      ? undefined
      : (frames.find(
          (candidate) => candidate.authority === "reviewed-library",
        ) ?? draftFrame);

  function navigate(next: AnalysisCursor) {
    setWalkthrough({ kind: "ready", examples, cursor: next });
    setFocus({ kind: "pinned" });
  }
  function chooseExample(id: ExampleId) {
    const target = examples.find((candidate) => candidate.id === id);
    if (target) navigate(AnalysisCursor.start(target));
  }
  function pick(candidate: Step) {
    return {
      onPointerEnter: () => setFocus({ kind: "preview", step: candidate }),
      onPointerLeave: () => setFocus({ kind: "pinned" }),
      onFocus: () => setFocus({ kind: "preview", step: candidate }),
      onBlur: () => setFocus({ kind: "pinned" }),
      onClick: () => navigate(cursor.seek(candidate)),
      onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          navigate(cursor.seek(candidate));
        }
      },
    };
  }
  function sourceLine(
    index: number,
    interactive: boolean,
    indent: number,
  ): JSX.Element[] {
    const tokens = KAST_SYNTAX[example.variant][index] ?? [];
    const first = tokens[0];
    if (!first) return [];
    const start = first.start;
    const end = start + (lines[index]?.length ?? 0);
    // Keep a signature's return type with its equals sign when it wraps.
    const signatureTail = lines[index]?.match(/\S+ =$/u)?.[0];
    const tailStart = end - (signatureTail?.length ?? 0);
    const boundaries = [
      ...new Set([
        start,
        start + indent,
        tailStart,
        end,
        ...frames
          .flatMap((candidate) => [
            candidate.anchor.start,
            candidate.anchor.end,
          ])
          .filter((offset) => offset > start && offset < end),
      ]),
    ].sort((a, b) => a - b);
    return boundaries.slice(0, -1).map((from, index) => {
      const to = boundaries[index + 1] ?? end;
      const candidate = frames
        .filter((frame) => frame.anchor.start <= from && frame.anchor.end >= to)
        .sort(
          (a, b) =>
            a.anchor.end - a.anchor.start - (b.anchor.end - b.anchor.start),
        )[0];
      const content = tokens
        .filter(
          (token) => token.start < to && token.start + token.text.length > from,
        )
        .map((token) => (
          <span
            key={token.start}
            style={{ color: `light-dark(${token.light}, ${token.dark})` }}
          >
            {token.text.slice(
              Math.max(0, from - token.start),
              Math.min(token.text.length, to - token.start),
            )}
          </span>
        ));
      const selected =
        example.source.slice(from, to).trim().length > 0 &&
        ((focused.anchor.start <= from && focused.anchor.end >= to) ||
          (focused.context.kind === "call" &&
            focused.context.anchor.start <= from &&
            focused.context.anchor.end >= to));
      return interactive &&
        candidate &&
        /[\p{L}\p{N}]/u.test(example.source.slice(from, to)) ? (
        <span
          key={from}
          role="button"
          tabIndex={0}
          className="vj-code-pick"
          data-focused={selected}
          aria-label={candidate.title}
          aria-pressed={candidate === step}
          {...pick(candidate)}
        >
          {content}
        </span>
      ) : (
        <span
          key={from}
          className={
            from === start && to === start + indent
              ? "vj-code-indent"
              : from === tailStart
                ? "vj-code-tail"
                : selected && !preview
                  ? "vj-code-selected"
                  : undefined
          }
        >
          {content}
        </span>
      );
    });
  }
  function excerpt(from: number, to: number, interactive = true) {
    return (
      <div className="vj-code">
        {lines.slice(from, to).map((text, index) => {
          const indent = text.length - text.trimStart().length;
          const offset =
            lines.slice(0, from + index).join("\n").length +
            (from + index > 0 ? 1 : 0);
          const active =
            focused.anchor.start < offset + text.length &&
            focused.anchor.end > offset;
          return (
            <div
              className="vj-source-line"
              key={from + index}
              data-line={from + index + 1}
              data-active={!preview && active}
            >
              <span className="vj-line-number" aria-hidden="true">
                {from + index + 1}
              </span>
              <code
                style={{
                  paddingInlineStart: `${indent + 2}ch`,
                  textIndent: "-2ch",
                }}
              >
                {text ? sourceLine(from + index, interactive, indent) : <br />}
              </code>
            </div>
          );
        })}
      </div>
    );
  }
  function customer(other = false) {
    return (
      <span className={other ? "vj-customer vj-other" : "vj-customer"}>
        <span className="vj-token" aria-hidden="true">
          {other ? "B" : "A"}
        </span>
        <span>
          <strong>{other ? "A different customer" : "Customer"}</strong>
          <small>{other ? 'Customer("Guest")' : "from createCustomer"}</small>
        </span>
      </span>
    );
  }
  function containment() {
    return (
      <div
        className={gap ? "vj-containment vj-uncertain" : "vj-containment"}
        data-vj-node="output"
        role={!preview && outputFrame ? "button" : undefined}
        tabIndex={!preview && outputFrame ? 0 : undefined}
        aria-label={!preview && outputFrame ? outputFrame.title : undefined}
        aria-pressed={
          !preview && outputFrame ? step === outputFrame : undefined
        }
        {...(!preview && outputFrame ? pick(outputFrame) : {})}
      >
        <div className="vj-container-label">
          {onEmailRoute
            ? "Returned email draft"
            : fork
              ? "Returned customer list"
              : "Returned email list"}
        </div>
        {gap ? (
          <div className="vj-unknown">
            <strong>?</strong>
            <b>Connection not established</b>
            <p>The source is unchanged. The map rule is missing.</p>
          </div>
        ) : fork && !onEmailRoute ? (
          customer()
        ) : (
          <div className="vj-email">
            <span>WelcomeEmail</span>
            <div className="vj-recipient">
              <small>recipient holds</small>
              {customer(different)}
            </div>
          </div>
        )}
      </div>
    );
  }

  if (preview)
    return (
      <div className="value-journey vj-overview not-prose">
        <div className="vj-overview-heading">
          <span className="vj-kicker">See the connection</span>
          <h2>A customer. An email draft. A list.</h2>
          <p>
            The names change as the customer is wrapped in other objects. The
            connection does not.
          </p>
        </div>
        <div className="vj-overview-grid">
          <div>
            {containment()}
            <p className="vj-caption">
              A marks a source origin, not a runtime object ID.
            </p>
          </div>
          <div>{excerpt(producerStart, lines.length, false)}</div>
        </div>
        <div className="vj-overview-foot">
          <p>
            Change one line. Find another use. See where an explanation must
            stop.
          </p>
          <a href="/explanation/value-journey">
            Open the source map <span aria-hidden="true">↗</span>
          </a>
        </div>
      </div>
    );

  return (
    <div
      className="value-journey vj-explorer not-prose"
      data-example={example.id}
      data-step={step.id}
      data-preview={focus.kind === "preview"}
    >
      <div className="vj-cases" role="group" aria-label="Prepared example">
        {(["e0", "constant", "fork", "gap"] as const).map((id) => {
          const candidate = examples.find((item) => item.id === id);
          return candidate ? (
            <button
              key={id}
              type="button"
              aria-pressed={example.id === id}
              onClick={() => chooseExample(id)}
            >
              {candidate.label}
            </button>
          ) : null;
        })}
      </div>
      <p className="vj-context" role="status">
        {example.description}
      </p>
      {fork && (
        <div
          className="vj-route-choice"
          role="group"
          aria-label="Choose a call context"
        >
          <span>Follow a call:</span>
          {example.paths.map((route) => (
            <button
              key={route.id}
              type="button"
              aria-pressed={path === route}
              onClick={() => navigate(cursor.select(route))}
            >
              {route.label} ↗
            </button>
          ))}
        </div>
      )}

      <article className="vj-workbench" aria-label="Connected source map">
        <div className="vj-bench-head">
          <strong>WelcomeEmails.kt</strong>
          <button
            type="button"
            aria-expanded={sourceVisible}
            aria-controls="vj-full-source"
            onClick={() => setSourceVisible(!sourceVisible)}
          >
            {sourceVisible ? "Hide source" : "Show source"}
          </button>
        </div>
        <div className="vj-split" data-source-visible={sourceVisible}>
          <div
            id="vj-full-source"
            className="vj-source-pane"
            role="region"
            aria-label="Full Kotlin source"
            hidden={!sourceVisible}
            ref={setSourcePane}
          >
            {excerpt(0, lines.length)}
          </div>
          <div className="vj-graph-pane">
            <p className="vj-interaction-hint">
              Hover or Tab to preview · Click or Enter to select · Esc to
              restore
            </p>
            <div
              className="vj-canvas"
              ref={setCanvas}
              data-focus={
                focused.authority === "reviewed-library"
                  ? "output"
                  : lineOf(focused.anchor) < draftStart
                    ? "producer"
                    : lineOf(focused.anchor) < batchStart
                      ? "draft"
                      : "batch"
              }
            >
              <svg className="vj-wires" aria-hidden="true">
                <defs>
                  <marker
                    id="vj-source-arrow"
                    viewBox="0 0 6 6"
                    refX="5"
                    refY="3"
                    markerWidth="5"
                    markerHeight="5"
                    orient="auto"
                  >
                    <path d="M0 0L6 3L0 6" fill="var(--vj-accent)" />
                  </marker>
                  <marker
                    id="vj-library-arrow"
                    viewBox="0 0 6 6"
                    refX="5"
                    refY="3"
                    markerWidth="5"
                    markerHeight="5"
                    orient="auto"
                  >
                    <path d="M0 0L6 3L0 6" fill="var(--vj-amber)" />
                  </marker>
                </defs>
                <path
                  d={wires.call}
                  className="vj-wire"
                  markerEnd="url(#vj-source-arrow)"
                />
                <path
                  d={different ? "" : wires.batch}
                  className="vj-wire"
                  markerEnd="url(#vj-source-arrow)"
                />
                <path
                  d={wires.output}
                  className={
                    onEmailRoute ? "vj-wire" : "vj-wire vj-wire-output"
                  }
                  markerEnd={
                    gap
                      ? undefined
                      : onEmailRoute
                        ? "url(#vj-source-arrow)"
                        : "url(#vj-library-arrow)"
                  }
                />
              </svg>
              <section
                className="vj-node vj-producer"
                data-vj-node="producer"
                role="button"
                tabIndex={0}
                aria-label="Create the customer"
                aria-pressed={step === origin}
                {...pick(origin)}
              >
                <div className="vj-node-head">
                  <span>Create the customer</span>
                </div>
                {excerpt(producerStart + 1, draftStart, false)}
                <div className="vj-node-foot">
                  {customer()}
                  <p>The function returns this customer.</p>
                </div>
              </section>
              <section
                className="vj-node vj-draft"
                data-vj-node="draft"
                role={draftFrame ? "button" : undefined}
                tabIndex={draftFrame ? 0 : undefined}
                aria-label="Hold the customer in an email draft"
                aria-pressed={draftFrame ? step === draftFrame : undefined}
                {...(draftFrame ? pick(draftFrame) : {})}
              >
                <div className="vj-node-head">
                  <span>Set the email recipient</span>
                </div>
                {excerpt(draftStart + 1, batchStart, false)}
                <div className="vj-node-foot">
                  <span className="vj-mini-email">
                    WelcomeEmail{" "}
                    <span>
                      recipient → <b>A</b>
                    </span>
                  </span>
                  <p>The draft holds a customer, not just a name.</p>
                </div>
              </section>
              <section
                className="vj-node vj-batch"
                data-vj-node="batch"
                role={batchFrame ? "button" : undefined}
                tabIndex={batchFrame ? 0 : undefined}
                aria-label={
                  fork ? "Collect the customers" : "Collect the email drafts"
                }
                aria-pressed={batchFrame ? step === batchFrame : undefined}
                {...(batchFrame ? pick(batchFrame) : {})}
              >
                <div className="vj-node-head">
                  <span>
                    {fork
                      ? "Collect the customers"
                      : "Collect the email drafts"}
                  </span>
                </div>
                {excerpt(batchStart + 1, lines.length, false)}
                <div className="vj-node-foot">
                  {different ? (
                    customer(true)
                  ) : gap ? (
                    <span className="vj-warning">
                      ? The list connection needs the map rule
                    </span>
                  ) : (
                    <span className="vj-mini-email">
                      {fork ? "List element" : "Draft recipient"}
                      <span>
                        → <b>A</b>
                      </span>
                    </span>
                  )}
                  <p>
                    {different
                      ? "The same list type. A different source origin."
                      : fork
                        ? "A separate call context, not a runtime split."
                        : "map repeats the operation for each signup."}
                  </p>
                </div>
              </section>
              <section className="vj-structure" aria-label="Returned value">
                {containment()}
                <p>
                  {different
                    ? 'This list holds drafts addressed to Customer("Guest"), not customer A.'
                    : gap
                      ? "The email’s recipient is connected to A. Its place in the returned list is not established."
                      : onEmailRoute
                        ? "draftWelcomeEmail returns a draft with customer A as its recipient."
                        : fork
                          ? "prepareCustomers can return customer A directly as a list element."
                          : "prepareEmails can return an email draft whose recipient is customer A."}
                </p>
                <small className="vj-transfer-basis">
                  {onEmailRoute
                    ? "Source binding"
                    : gap
                      ? "Missing support: map → list element"
                      : "List connection: reviewed map rule"}
                </small>
              </section>
            </div>
          </div>
        </div>
        <div className="vj-graph-caption">
          <span>
            A marks a source origin, not an object observed at runtime.
          </span>
          <span>
            {focus.kind === "preview"
              ? `Preview: ${focused.title}`
              : "Highlights follow your selection, not execution."}
          </span>
        </div>

        <section
          className={stopped ? "vj-inspector vj-inspector-gap" : "vj-inspector"}
          aria-label="Selected connection"
        >
          <div className="vj-explanation">
            <h3>
              {stopped
                ? "The explanation stops before the list."
                : atEnd && different
                  ? "This list gets a different customer."
                  : step.title}
            </h3>
            <p>
              {atEnd && path.ending.kind !== "expected-end"
                ? path.ending.explanation
                : step.explanation}
            </p>
            <p className="vj-support">
              {stopped
                ? "Missing support: map → list element."
                : step.authority === "reviewed-library"
                  ? "Support: reviewed map rule, not the call binding alone."
                  : "Support: source binding, not matching names."}
            </p>
          </div>
          <div className="vj-controls">
            <button
              type="button"
              disabled={position === 0}
              onClick={() => {
                const previous = frames[position - 1];
                if (previous) navigate(cursor.seek(previous));
              }}
            >
              ← Back
            </button>
            <span>
              {position + 1} / {frames.length}
            </span>
            <button
              className="vj-next"
              type="button"
              disabled={atEnd}
              onClick={() => {
                const next = frames[position + 1];
                if (next) navigate(cursor.seek(next));
              }}
            >
              {atEnd ? "End" : "Next"} <span aria-hidden="true">→</span>
            </button>
          </div>
        </section>
      </article>
      <nav className="vj-steps" aria-label="Connections in this route">
        {frames.map((candidate, index) => (
          <button
            type="button"
            key={candidate.id}
            aria-current={candidate === step ? "step" : undefined}
            {...pick(candidate)}
          >
            <span>{String(index + 1).padStart(2, "0")}</span>
            {candidate.title}
          </button>
        ))}
      </nav>

      <p className="vj-reading-note">
        {different
          ? "Changing the returned expression changes which customer the list can hold. The original customer’s email-recipient route still exists elsewhere in this example."
          : gap
            ? "The call and field connections remain. Without support for the library step, this illustration stops rather than guessing that the list carries the customer."
            : fork
              ? "One function can be called from different places. Each route keeps its own call context; this picture does not show one object being shared between them."
              : "A call receives a result. A field holds a value. The reviewed map rule connects the operation’s result to a possible list element. Select a connection to see its source lines."}
      </p>

      <details className="vj-details">
        <summary>Inspect the source ranges and evidence limits</summary>
        <div className="vj-details-grid">
          <div>
            <h3>An authored example, not a live result</h3>
            <p>
              These readable names and line breaks adapt the frozen{" "}
              {example.variant} fixture. Every highlighted expression has an
              admitted source range. The original fixture and route identities
              stay unchanged.
            </p>
            <p>
              Source connections and reviewed library transfers have different
              support. The steps below retain that distinction; neither is a
              captured compiler witness.
            </p>
            <ol>
              {path.steps.map((candidate) => (
                <li key={candidate.id}>
                  <strong>{candidate.title}</strong>
                  <span>
                    {candidate.authority === "compiler"
                      ? "Source binding"
                      : "Reviewed library transfer"}{" "}
                    · {candidate.operation}
                  </span>
                  <code>{candidate.anchor.text}</code>
                </li>
              ))}
            </ol>
            <dl>
              <dt>Selected range</dt>
              <dd>
                UTF-16 [{step.anchor.start}, {step.anchor.end})
              </dd>
              <dt>Displayed source SHA-256</dt>
              <dd>
                <code>{example.sha256}</code>
              </dd>
              <dt>Original fixture SHA-256</dt>
              <dd>
                <code>{example.fixtureSha256}</code>
              </dd>
              <dt>Compiler identity / coverage ledger</dt>
              <dd>Not captured</dd>
            </dl>
            <a href="/explanation/value-journey-evidence">
              Read the evidence boundaries →
            </a>
          </div>
        </div>
      </details>
    </div>
  );
}
