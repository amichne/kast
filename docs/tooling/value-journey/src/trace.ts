import e0 from "../../../../relation/intellij/testData/r1-value-flow/E0.kt?raw";
import e3 from "../../../../relation/intellij/testData/r1-value-flow/E3.kt?raw";
import e4 from "../../../../relation/intellij/testData/r1-value-flow/E4.kt?raw";
import oracle from "../../../../relation/intellij/testData/r1-value-flow/oracle.json";

export type Detail = "story" | "normal" | "proof";
export type ExampleId = "e0" | "gap" | "fork" | "constant";
export type Authority = "compiler" | "reviewed-library";

/** UTF-16, half-open coordinates admitted against one immutable source snapshot. */
export class SourceAnchor {
  private constructor(
    readonly source: string,
    readonly start: number,
    readonly end: number,
  ) {}

  static admit(source: string, start: number, text: string): AnchorAdmission {
    if (
      !Number.isInteger(start) ||
      start < 0 ||
      !text ||
      source.slice(start, start + text.length) !== text
    )
      return { kind: "rejected", code: "INVALID_ANCHOR" };
    return {
      kind: "admitted",
      value: new SourceAnchor(source, start, start + text.length),
    };
  }

  get text(): string {
    return this.source.slice(this.start, this.end);
  }
}

type AnchorAdmission =
  | { readonly kind: "admitted"; readonly value: SourceAnchor }
  | { readonly kind: "rejected"; readonly code: "INVALID_ANCHOR" };

export interface Step {
  readonly id: string;
  readonly anchor: SourceAnchor;
  readonly title: string;
  readonly explanation: string;
  readonly value: string;
  readonly authority: Authority;
  readonly operation: string;
  readonly importance: "landmark" | "normal" | "detail";
  readonly context:
    | { readonly kind: "local" }
    | {
        readonly kind: "call";
        readonly title: string;
        readonly anchor: SourceAnchor;
      }
    | { readonly kind: "callback" };
}

export type Ending =
  | { readonly kind: "expected-end"; readonly explanation: string }
  | {
      readonly kind: "unresolved";
      readonly obligation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT";
      readonly explanation: string;
    }
  | { readonly kind: "excluded"; readonly explanation: string };

export interface TracePath {
  readonly id: string;
  readonly label: string;
  readonly steps: readonly [Step, ...Step[]];
  readonly ending: Ending;
}

export interface Example {
  readonly id: ExampleId;
  readonly label: string;
  readonly description: string;
  readonly source: string;
  readonly variant: "E0" | "E3" | "E4";
  readonly sha256: string;
  /** An authored expectation is never a retained compiler witness or a coverage ledger. */
  readonly basis: { readonly kind: "authored-oracle"; readonly path: string };
  readonly paths: readonly [TracePath, ...TracePath[]];
}

export type ExampleAdmission =
  | {
      readonly kind: "ready";
      readonly examples: readonly [Example, ...Example[]];
    }
  | {
      readonly kind: "rejected";
      readonly code: "SOURCE_CHANGED" | "INVALID_ANCHOR";
    };

/** No source search or semantic inference: these are authored coordinates in the frozen fixtures. */
export async function loadExamples(): Promise<ExampleAdmission> {
  const snapshots = [
    { variant: "E0", source: e0 },
    { variant: "E3", source: e3 },
    { variant: "E4", source: e4 },
  ] as const;
  const digest = async (source: string): Promise<string> => {
    const bytes = await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(source),
    );
    return Array.from(new Uint8Array(bytes), (byte) =>
      byte.toString(16).padStart(2, "0"),
    ).join("");
  };
  const digests = await Promise.all([digest(e0), digest(e3), digest(e4)]);
  for (const [index, snapshot] of snapshots.entries()) {
    if (
      oracle.variants.find((variant) => variant.id === snapshot.variant)
        ?.sha256 !== digests[index]
    )
      return { kind: "rejected", code: "SOURCE_CHANGED" };
  }

  const build = (
    variant: "E0" | "E3" | "E4",
    source: string,
    sha256: string,
  ): ExampleAdmission => {
    const seed = SourceAnchor.admit(source, 232, "ClientValue(id = id)");
    const call = SourceAnchor.admit(source, 338, "toClientValue()");
    const envelope = SourceAnchor.admit(
      source,
      313,
      "ClientEnvelope(payload = toClientValue())",
    );
    if (seed.kind === "rejected") return seed;
    if (call.kind === "rejected") return call;
    if (envelope.kind === "rejected") return envelope;
    const local = { kind: "local" } as const;
    const head: Step = {
      id: "seed.ClientValue.result",
      anchor: seed.value,
      title: "A value begins here",
      explanation:
        "Follow only the ClientValue produced by this constructor. Not the WireValue, its id, or the enclosing object.",
      value: "ClientValue",
      authority: "compiler",
      operation: "PRODUCER",
      importance: "landmark",
      context: local,
    };
    const returned: Step = {
      ...head,
      id: "toClientValue.RETURN",
      title: "Returned by toClientValue",
      explanation:
        "The expression body returns the same ClientValue. Its identity is preserved across this return.",
      operation: "SEED_RETURN",
      importance: "detail",
    };
    const wrapper: Step[] = [
      {
        ...head,
        id: "toClientValue.call.result",
        anchor: call.value,
        title: "Returned to this exact call",
        explanation:
          "The produced value becomes the result of this toClientValue() call, not every call with the same name.",
        operation: "RETURN",
        importance: "normal",
        context: {
          kind: "call",
          title: "WireValue.toClientValue",
          anchor: seed.value,
        },
      },
      {
        ...head,
        id: "ClientEnvelope.payload.argument",
        anchor: call.value,
        title: "Passed as payload",
        explanation:
          "This call result is bound to the payload parameter of the selected ClientEnvelope constructor.",
        operation: "ARGUMENT",
        importance: "detail",
      },
      {
        ...head,
        id: "ClientEnvelope.result.payload",
        anchor: envelope.value,
        title: "Stored in the payload field",
        explanation:
          "The envelope carries the original ClientValue in .payload. The envelope itself is not the tracked value.",
        value: "ClientEnvelope.payload",
        operation: "CONSTRUCT_FIELD",
        importance: "landmark",
      },
      {
        ...head,
        id: "toClientEnvelope.RETURN.payload",
        anchor: envelope.value,
        title: "Returned inside an envelope",
        explanation:
          "toClientEnvelope returns an envelope whose payload carries the same ClientValue.",
        value: "ClientEnvelope.payload",
        operation: "SEED_RETURN",
        importance: "detail",
      },
    ];
    const basis = {
      kind: "authored-oracle",
      path: "relation/intellij/testData/r1-value-flow/oracle.json",
    } as const;
    const base = { source, variant, sha256, basis };
    const wrapperPath: TracePath = {
      id: "wrapper",
      label: "Envelope payload",
      steps: [head, returned, ...wrapper],
      ending: {
        kind: "expected-end",
        explanation:
          "The authored route ends at the returned payload. This is not an investigation-completeness claim.",
      },
    };
    if (variant === "E4")
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "constant",
            label: "E4 · Independent constant",
            description:
              "Why does the original value not reach this map result?",
            paths: [
              {
                ...wrapperPath,
                ending: {
                  kind: "excluded",
                  explanation:
                    'convert constructs a separate ClientValue("constant"). The oracle forbids an edge from the original seed to that value or the convert result.',
                },
              },
            ],
          },
        ],
      };
    const callback = SourceAnchor.admit(
      source,
      variant === "E0" ? 436 : 433,
      variant === "E0" ? "it.toClientEnvelope()" : "it.toClientValue()",
    );
    const map = SourceAnchor.admit(
      source,
      variant === "E0" ? 424 : 421,
      variant === "E0"
        ? "items.map { it.toClientEnvelope() }"
        : "items.map { it.toClientValue() }",
    );
    if (callback.kind === "rejected") return callback;
    if (map.kind === "rejected") return map;
    const payload = variant === "E0" ? "ClientEnvelope.payload" : "ClientValue";
    const callbackStep: Step = {
      ...head,
      id:
        variant === "E0"
          ? "toClientEnvelope.call.result.payload"
          : "callback.toClientValue.call.result",
      anchor: callback.value,
      title: "Returned to the callback",
      explanation: `The exact call result carries ${payload}. The callback body and the named function that supplies it are distinct owners.`,
      value: payload,
      operation: "RETURN",
      importance: "normal",
      context: {
        kind: "call",
        title:
          variant === "E0"
            ? "WireValue.toClientEnvelope"
            : "WireValue.toClientValue",
        anchor: variant === "E0" ? envelope.value : seed.value,
      },
    };
    const callbackReturn: Step = {
      ...callbackStep,
      id: variant === "E0" ? "callback.RETURN.payload" : "callback.RETURN",
      title: "Bound to map’s transform",
      explanation:
        "The lambda result carries the tracked value. Compiler binding identifies the transform formal; it does not establish output-element propagation.",
      operation: "CALLBACK_RETURN",
      importance: "landmark",
      context: { kind: "callback" },
    };
    const element: Step = {
      ...callbackReturn,
      id:
        variant === "E0"
          ? "convert.map.result.Element.payload"
          : "convert.map.result.Element",
      anchor: map.value,
      title: "Callback result → possible output element",
      explanation:
        "The reviewed stdlib map rule relates a transform result to a possible output element. Compiler binding alone cannot establish this transfer.",
      value: `List.Element<${payload}>`,
      authority: "reviewed-library",
      operation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
    };
    const final: Step = {
      ...element,
      id:
        variant === "E0"
          ? "convert.RETURN.Element.payload"
          : "convert.RETURN.Element",
      title: "Returned from convert",
      explanation:
        "The returned list may contain an element carrying the original value. Neither the whole list nor the whole envelope becomes that value.",
      authority: "compiler",
      operation: "SEED_RETURN",
      context: local,
    };
    const journey: TracePath = {
      id: "map",
      label:
        variant === "E0" ? "Envelope → map element" : "Direct → map element",
      steps: [
        head,
        returned,
        ...(variant === "E0" ? wrapper : []),
        callbackStep,
        callbackReturn,
        element,
        final,
      ],
      ending: {
        kind: "expected-end",
        explanation:
          "End of the authored expected route. A connected path does not establish complete investigation coverage.",
      },
    };
    if (variant === "E3")
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "fork",
            label: "E3 · Two continuations",
            description:
              "One producer, two uses. Choose a continuation without losing the common prefix.",
            paths: [journey, wrapperPath],
          },
        ],
      };
    return {
      kind: "ready",
      examples: [
        {
          ...base,
          id: "e0",
          label: "E0 · Value journey",
          description: "Where can this produced ClientValue go?",
          paths: [journey],
        },
        {
          ...base,
          id: "gap",
          label: "E0 · Missing proof",
          description: "Same source. One deliberately withheld library rule.",
          paths: [
            {
              ...journey,
              steps: [head, returned, ...wrapper, callbackStep, callbackReturn],
              ending: {
                kind: "unresolved",
                obligation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
                explanation:
                  "The callback result is known, but its relationship to a map output element is not admitted in this simulated gap. No downstream step is shown.",
              },
            },
          ],
        },
      ],
    };
  };
  const positive = build("E0", e0, digests[0]);
  const fork = build("E3", e3, digests[1]);
  const constant = build("E4", e4, digests[2]);
  if (positive.kind === "rejected") return positive;
  if (fork.kind === "rejected") return fork;
  if (constant.kind === "rejected") return constant;
  return {
    kind: "ready",
    examples: [
      positive.examples[0],
      ...positive.examples.slice(1),
      ...fork.examples,
      ...constant.examples,
    ],
  };
}

/** A cursor can refer only to a frame in its selected path. */
export class AnalysisCursor {
  private constructor(
    readonly example: Example,
    readonly path: TracePath,
    readonly step: Step,
  ) {}

  static start(example: Example): AnalysisCursor {
    return new AnalysisCursor(
      example,
      example.paths[0],
      example.paths[0].steps[0],
    );
  }

  get index(): number {
    return this.path.steps.indexOf(this.step);
  }
  get atEnd(): boolean {
    return this.step === this.path.steps.at(-1);
  }

  visible(detail: Detail): readonly Step[] {
    return this.path.steps.filter(
      (step) =>
        step === this.path.steps.at(-1) ||
        detail === "proof" ||
        step.importance === "landmark" ||
        (detail === "normal" && step.importance === "normal"),
    );
  }

  move(direction: "previous" | "next", detail: Detail): AnalysisCursor {
    const candidates = this.visible(detail);
    const next =
      direction === "next"
        ? candidates.find((step) => this.path.steps.indexOf(step) > this.index)
        : candidates.findLast(
            (step) => this.path.steps.indexOf(step) < this.index,
          );
    return next ? new AnalysisCursor(this.example, this.path, next) : this;
  }

  select(path: TracePath): AnalysisCursor {
    if (!this.example.paths.includes(path)) return this;
    let shared = 0;
    while (
      shared <= this.index &&
      path.steps[shared]?.id === this.path.steps[shared]?.id
    )
      shared++;
    return new AnalysisCursor(
      this.example,
      path,
      path.steps[Math.max(0, shared - 1)] ?? path.steps[0],
    );
  }

  seek(step: Step): AnalysisCursor {
    return this.path.steps.includes(step)
      ? new AnalysisCursor(this.example, this.path, step)
      : this;
  }
}
