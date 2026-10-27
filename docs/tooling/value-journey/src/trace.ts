import e0 from "../../../../relation/intellij/testData/r1-value-flow/E0.kt?raw";
import e3 from "../../../../relation/intellij/testData/r1-value-flow/E3.kt?raw";
import e4 from "../../../../relation/intellij/testData/r1-value-flow/E4.kt?raw";
import oracle from "../../../../relation/intellij/testData/r1-value-flow/oracle.json";
import { displaySource } from "./display-source";
import type { FixtureVariant } from "./display-source";

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
  readonly variant: FixtureVariant;
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
    variant: FixtureVariant,
    fixtureSource: string,
    sha256: string,
    fixtureSha256: string,
  ): ExampleAdmission => {
    const source = displaySource(fixtureSource, variant);
    const anchor = (start: number, text: string): AnchorAdmission => {
      const original = SourceAnchor.admit(fixtureSource, start, text);
      if (original.kind === "rejected") return original;
      return SourceAnchor.admit(
        source,
        displaySource(fixtureSource.slice(0, start), variant).length,
        displaySource(text, variant),
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
      title: "A customer starts here",
      explanation:
        "createCustomer builds a customer from the signup’s name. Follow this source origin, marked A, through the calls that use it.",
      value: "Customer",
      authority: "compiler",
      operation: "PRODUCER",
      importance: "landmark",
      context: local,
    };
    const returned: Step = {
      ...head,
      id: "toClientValue.RETURN",
      title: "Returned by createCustomer",
      explanation: "The function returns the customer to its caller.",
      operation: "SEED_RETURN",
      importance: "detail",
    };
    const wrapper: Step[] = [
      {
        ...head,
        id: "toClientValue.call.result",
        anchor: call.value,
        title: "This call receives the customer",
        explanation:
          "The call inside draftWelcomeEmail receives the customer returned by createCustomer. A matching name alone would not establish this connection.",
        operation: "RETURN",
        importance: "normal",
        context: {
          kind: "call",
          title: "Signup.createCustomer",
          anchor: seed.value,
        },
      },
      {
        ...head,
        id: "ClientEnvelope.payload.argument",
        anchor: call.value,
        title: "Passed as the recipient",
        explanation:
          "The WelcomeEmail constructor receives the customer as its recipient argument.",
        operation: "ARGUMENT",
        importance: "detail",
      },
      {
        ...head,
        id: "ClientEnvelope.result.payload",
        anchor: listing.value,
        title: "An email draft holds the customer",
        explanation:
          "The recipient field holds the customer, not just the customer’s name. The surrounding email draft is a container; it is not another customer.",
        value: "WelcomeEmail.recipient",
        operation: "CONSTRUCT_FIELD",
        importance: "landmark",
      },
      {
        ...head,
        id: "toClientEnvelope.RETURN.payload",
        anchor: listing.value,
        title: "Returned inside an email draft",
        explanation:
          "draftWelcomeEmail returns a draft whose recipient carries the customer.",
        value: "WelcomeEmail.recipient",
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
      label: "Email recipient",
      steps: [head, returned, ...wrapper],
      ending: {
        kind: "expected-end",
        explanation: "This route ends at the returned email’s recipient.",
      },
    };
    if (variant === "E4")
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "constant",
            label: "Change one line",
            description:
              "The list now gets a new Guest customer. The original customer still has another use, but not in this list.",
            paths: [
              {
                ...wrapperPath,
                ending: {
                  kind: "excluded",
                  explanation:
                    'prepareEmails constructs a separate Customer("Guest"). The original customer does not reach this list, even though the list’s declared type stays the same.',
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
    const value = variant === "E0" ? "WelcomeEmail.recipient" : "Customer";
    const callbackStep: Step = {
      ...head,
      id:
        variant === "E0"
          ? "toClientEnvelope.call.result.payload"
          : "callback.toClientValue.call.result",
      anchor: callback.value,
      title: "The repeated operation receives it",
      explanation:
        variant === "E0"
          ? "The operation inside map calls draftWelcomeEmail for a signup and receives the email draft holding the customer."
          : "This separate call inside map receives a customer directly. It is a different call context from the email recipient route, not one runtime object splitting in two.",
      value,
      operation: "RETURN",
      importance: "normal",
      context: {
        kind: "call",
        title:
          variant === "E0"
            ? "Signup.draftWelcomeEmail"
            : "Signup.createCustomer",
        anchor: variant === "E0" ? listing.value : seed.value,
      },
    };
    const callbackReturn: Step = {
      ...callbackStep,
      id: variant === "E0" ? "callback.RETURN.payload" : "callback.RETURN",
      title: "The operation inside map returns it",
      explanation:
        "The operation inside map returns its result. Connecting that result to a list element needs an additional rule about map; the call alone is not enough.",
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
      title: "The list can hold this result",
      explanation:
        "The reviewed rule for map connects the callback result to a possible list element.",
      value: `List.Element<${value}>`,
      authority: "reviewed-library",
      operation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
    };
    const final: Step = {
      ...element,
      id:
        variant === "E0"
          ? "convert.RETURN.Element.payload"
          : "convert.RETURN.Element",
      title: "The returned list keeps the connection",
      explanation:
        "The returned list can carry this source origin. That does not tell us whether this program ran, how many elements it produced, or every other place the value is used.",
      authority: "compiler",
      operation: "SEED_RETURN",
      context: local,
    };
    const journey: TracePath = {
      id: "map",
      label:
        variant === "E0" ? "Email drafts in the list" : "Customers in the list",
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
            label: "Find the other use",
            description:
              "Two separate calls use createCustomer: one makes an email recipient; the other returns customers directly in a list.",
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
          label: "Follow a customer",
          description:
            "A signup becomes a customer, an email draft holds that customer, and a list can hold the draft.",
          paths: [journey],
        },
        {
          ...base,
          id: "gap",
          label: "Remove one rule",
          description:
            "The source is unchanged. Withhold the map rule and the list connection can no longer be established.",
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
  const positive = build(
    "E0",
    e0,
    await digest(displaySource(e0, "E0")),
    digests[0],
  );
  const fork = build(
    "E3",
    e3,
    await digest(displaySource(e3, "E3")),
    digests[1],
  );
  const constant = build(
    "E4",
    e4,
    await digest(displaySource(e4, "E4")),
    digests[2],
  );
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
