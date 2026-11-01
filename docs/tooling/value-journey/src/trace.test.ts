import { describe, expect, it } from "vitest";

import { AnalysisCursor, loadExamples, SourceAnchor } from "./trace";

const admitted = await loadExamples();
if (admitted.kind === "rejected") throw new Error(admitted.code);
const examples = admitted.examples;

describe("source-local value journey", () => {
  it("presents readable signup names while retaining exact source anchors", () => {
    expect(examples[0].source).toBe(`data class Signup(val name: String?)
data class Customer(val name: String?)
data class WelcomeEmail(val recipient: Customer)

fun Signup.createCustomer(): Customer =
    Customer(name = name)
fun Signup.draftWelcomeEmail(): WelcomeEmail =
    WelcomeEmail(recipient = createCustomer())
fun prepareEmails(signups: List<Signup>): List<WelcomeEmail> =
    signups.map { signup ->
        signup.draftWelcomeEmail()
    }
`);
    for (const example of examples) {
      for (const path of example.paths) {
        for (const step of path.steps) {
          expect(example.source.slice(step.anchor.start, step.anchor.end)).toBe(
            step.anchor.text,
          );
        }
      }
    }
    expect(
      examples.find((example) => example.id === "constant")?.source,
    ).toContain('WelcomeEmail(Customer("Guest"))');
  });

  it("advances across the reviewed rule without widening the tracked value", () => {
    let cursor = AnalysisCursor.start(examples[0]);
    for (let i = 0; i < 8; i++) cursor = cursor.move("next", "proof");
    expect(cursor.index).toBe(8);
    expect(cursor.step.authority).toBe("reviewed-library");
    expect(cursor.step.value).toBe("List.Element<WelcomeEmail.recipient>");
    expect(cursor.step.anchor.text).toBe(
      "signups.map { signup ->\n        signup.draftWelcomeEmail()\n    }",
    );
    expect(cursor.move("next", "proof").step.id).toBe(
      "convert.RETURN.Element.payload",
    );
  });

  it("compresses navigation, not evidence, and never skips a missing proof", () => {
    const gap = examples.find((example) => example.id === "gap");
    expect(gap).toBeDefined();
    if (!gap) return;
    let cursor = AnalysisCursor.start(gap);
    expect(cursor.visible("story").map((step) => step.id)).toEqual([
      "seed.ClientValue.result",
      "ClientEnvelope.result.payload",
      "callback.RETURN.payload",
    ]);
    cursor = cursor.move("next", "story").move("next", "story");
    expect(cursor.atEnd).toBe(true);
    expect(cursor.path.ending.kind).toBe("unresolved");
    expect(cursor.path.steps).toHaveLength(8);
    expect(cursor.move("next", "proof")).toBe(cursor);
    expect(cursor.move("previous", "normal").step.id).toBe(
      "toClientEnvelope.call.result.payload",
    );
  });

  it("switches forks at the common prefix and rejects a foreign frame", () => {
    const fork = examples.find((example) => example.id === "fork");
    if (!fork) throw new Error("Missing fork fixture");
    let cursor = AnalysisCursor.start(fork);
    for (let i = 0; i < 4; i++) cursor = cursor.move("next", "proof");
    const other = fork.paths[1];
    if (!other) throw new Error("Missing continuation");
    cursor = cursor.select(other);
    expect(cursor.step.id).toBe("toClientValue.RETURN");
    expect(cursor.move("next", "proof").step.id).toBe(
      "toClientValue.call.result",
    );
    expect(cursor.seek(examples[0].paths[0].steps[0])).toBe(cursor);
  });

  it("retains exact oracle route identities and rejects changed source ranges", async () => {
    const oracle =
      await import("../../../../relation/intellij/testData/r1-value-flow/oracle.json");
    expect(examples[0].paths[0].steps.map((step) => step.id)).toEqual(
      oracle.default.variants[0]?.expected_path,
    );
    expect(examples[0].fixtureSha256).toBe(oracle.default.variants[0]?.sha256);
    expect(examples[0].sha256).not.toBe(examples[0].fixtureSha256);
    expect(SourceAnchor.admit(examples[0].source, 232, "Signup")).toEqual({
      kind: "rejected",
      code: "INVALID_ANCHOR",
    });
    expect(SourceAnchor.admit(examples[0].source, -1, "x")).toEqual({
      kind: "rejected",
      code: "INVALID_ANCHOR",
    });
    expect(SourceAnchor.admit(examples[0].source, 1.5, "x")).toEqual({
      kind: "rejected",
      code: "INVALID_ANCHOR",
    });
    expect(SourceAnchor.admit(examples[0].source, 0, "")).toEqual({
      kind: "rejected",
      code: "INVALID_ANCHOR",
    });
  });

  it("does not connect the original seed to an independent constant", () => {
    const constant = examples.find((example) => example.id === "constant");
    if (!constant) throw new Error("Missing constant fixture");
    expect(constant.paths[0].steps).toHaveLength(6);
    expect(constant.paths[0].steps.at(-1)?.value).toBe(
      "WelcomeEmail.recipient",
    );
    expect(constant.paths[0].ending.kind).toBe("excluded");
    expect(
      constant.paths[0].steps.every((step) => step.anchor.end <= 354),
    ).toBe(true);
    expect(constant.basis.kind).toBe("authored-oracle");
  });
});
