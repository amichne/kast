import e0 from "../../../../relation/intellij/testData/r1-value-flow/E0.kt?raw";
import e3 from "../../../../relation/intellij/testData/r1-value-flow/E3.kt?raw";
import e4 from "../../../../relation/intellij/testData/r1-value-flow/E4.kt?raw";
import oracle from "../../../../relation/intellij/testData/r1-value-flow/oracle.json";
import { petStoreNames } from "./pet-store";

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
  readonly fixtureSha256: string;
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
    fixtureSource: string,
    sha256: string,
    fixtureSha256: string,
  ): ExampleAdmission => {
    const source = petStoreNames(fixtureSource);
    const anchor = (start: number, text: string): AnchorAdmission => {
      const original = SourceAnchor.admit(fixtureSource, start, text);
      if (original.kind === "rejected") return original;
      return SourceAnchor.admit(
        source,
        petStoreNames(fixtureSource.slice(0, start)).length,
        petStoreNames(text),
      );
    };
    const seed = anchor(232, "ClientValue(id = id)");
    const call = anchor(338, "toClientValue()");
    const listing = anchor(313, "ClientEnvelope(payload = toClientValue())");
    if (seed.kind === "rejected") return seed;
    if (call.kind === "rejected") return call;
    if (listing.kind === "rejected") return listing;
    const local = { kind: "local" } as const;
    const head: Step = {
      id: "seed.ClientValue.result",
      anchor: seed.value,
      title: "Start with this value",
      explanation: "This constructor creates the Pet that we will trace.",
      value: "Pet",
      authority: "compiler",
      operation: "PRODUCER",
      importance: "landmark",
      context: local,
    };
    const returned: Step = {
      ...head,
      id: "toClientValue.RETURN",
      title: "Returned by toPet",
      explanation: "The helper returns this Pet to its caller.",
      operation: "SEED_RETURN",
      importance: "detail",
    };
    const wrapper: Step[] = [
      {
        ...head,
        id: "toClientValue.call.result",
        anchor: call.value,
        title: "Returned to this exact call",
        explanation: "This call receives the value from the helper.",
        operation: "RETURN",
        importance: "normal",
        context: {
          kind: "call",
          title: "PetRecord.toPet",
          anchor: seed.value,
        },
      },
      {
        ...head,
        id: "ClientEnvelope.payload.argument",
        anchor: call.value,
        title: "Passed as pet",
        explanation: "The constructor receives the value as its pet argument.",
        operation: "ARGUMENT",
        importance: "detail",
      },
      {
        ...head,
        id: "ClientEnvelope.result.payload",
        anchor: listing.value,
        title: "Stored in the pet field",
        explanation: "The object carries our value in its pet field.",
        value: "PetListing.pet",
        operation: "CONSTRUCT_FIELD",
        importance: "landmark",
      },
      {
        ...head,
        id: "toClientEnvelope.RETURN.payload",
        anchor: listing.value,
        title: "Returned inside a listing",
        explanation:
          "toPetListing returns a listing whose pet carries the same Pet.",
        value: "PetListing.pet",
        operation: "SEED_RETURN",
        importance: "detail",
      },
    ];
    const basis = {
      kind: "authored-oracle",
      path: "relation/intellij/testData/r1-value-flow/oracle.json",
    } as const;
    const base = { source, variant, sha256, fixtureSha256, basis };
    const wrapperPath: TracePath = {
      id: "wrapper",
      label: "Listing pet",
      steps: [head, returned, ...wrapper],
      ending: {
        kind: "expected-end",
        explanation: "This example ends at the returned pet.",
      },
    };
    if (variant === "E4")
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "constant",
            label: "Return a different value",
            description:
              "A different value has the same type. Does the original still reach the list?",
            paths: [
              {
                ...wrapperPath,
                ending: {
                  kind: "excluded",
                  explanation:
                    'convert constructs a separate Pet("constant"). The original value does not reach this list.',
                },
              },
            ],
          },
        ],
      };
    const callback = anchor(
      variant === "E0" ? 436 : 433,
      variant === "E0" ? "it.toClientEnvelope()" : "it.toClientValue()",
    );
    const map = anchor(
      variant === "E0" ? 424 : 421,
      variant === "E0"
        ? "items.map { it.toClientEnvelope() }"
        : "items.map { it.toClientValue() }",
    );
    if (callback.kind === "rejected") return callback;
    if (map.kind === "rejected") return map;
    const pet = variant === "E0" ? "PetListing.pet" : "Pet";
    const callbackStep: Step = {
      ...head,
      id:
        variant === "E0"
          ? "toClientEnvelope.call.result.payload"
          : "callback.toClientValue.call.result",
      anchor: callback.value,
      title: "Returned to the callback",
      explanation: `The helper returns ${pet} inside the map callback.`,
      value: pet,
      operation: "RETURN",
      importance: "normal",
      context: {
        kind: "call",
        title: variant === "E0" ? "PetRecord.toPetListing" : "PetRecord.toPet",
        anchor: variant === "E0" ? listing.value : seed.value,
      },
    };
    const callbackReturn: Step = {
      ...callbackStep,
      id: variant === "E0" ? "callback.RETURN.payload" : "callback.RETURN",
      title: "The callback returns it",
      explanation:
        "The callback returns our value inside its result. The next step needs the rule for map.",
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
      title: "Into a list element",
      explanation:
        "The reviewed rule for map connects the callback result to a possible list element.",
      value: `List.Element<${pet}>`,
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
      explanation: "A returned list element can carry our original value.",
      authority: "compiler",
      operation: "SEED_RETURN",
      context: local,
    };
    const journey: TracePath = {
      id: "map",
      label:
        variant === "E0" ? "Listing → map element" : "Direct → map element",
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
          "The route reaches the returned list. The example ends here.",
      },
    };
    if (variant === "E3")
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "fork",
            label: "Use the value twice",
            description:
              "One producer has two uses. Choose a route to see where each leads.",
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
          label: "Follow the value",
          description: "Where does this value go after the helper returns it?",
          paths: [journey],
        },
        {
          ...base,
          id: "gap",
          label: "Remove a library rule",
          description:
            "The code stays the same. This illustration removes the rule for map.",
          paths: [
            {
              ...journey,
              steps: [head, returned, ...wrapper, callbackStep, callbackReturn],
              ending: {
                kind: "unresolved",
                obligation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
                explanation:
                  "The callback returns the value. Without the rule for map, this example cannot connect it to a list element.",
              },
            },
          ],
        },
      ],
    };
  };
  const positive = build("E0", e0, await digest(petStoreNames(e0)), digests[0]);
  const fork = build("E3", e3, await digest(petStoreNames(e3)), digests[1]);
  const constant = build("E4", e4, await digest(petStoreNames(e4)), digests[2]);
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
