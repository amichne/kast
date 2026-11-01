export type FixtureVariant = "E0" | "E3" | "E4";

/** Readable display only; the frozen fixtures and oracle retain their identities.
 * Replacements are prefix-stable at every authored anchor boundary so admission
 * can translate coordinates without searching for a similar-looking expression.
 */
export function displaySource(text: string, variant: FixtureVariant): string {
  const names: Readonly<Record<string, string>> = {
    WireValue: "Signup",
    ClientValue: "Customer",
    ClientEnvelope: "WelcomeEmail",
    toClientValue: "createCustomer",
    toClientEnvelope: "draftWelcomeEmail",
    payload: "recipient",
    id: "name",
    items: "signups",
    it: "signup",
    convert: variant === "E3" ? "prepareCustomers" : "prepareEmails",
  };
  return text
    .replace(
      /\b(WireValue|ClientValue|ClientEnvelope|toClientValue|toClientEnvelope|payload|id|items|it|convert)\b/g,
      (name) => names[name] ?? name,
    )
    .replace("package kast.repro\n\n", "")
    .replaceAll("internal ", "")
    .replaceAll('"constant"', '"Guest"')
    .replaceAll("): Customer = ", "): Customer =\n    ")
    .replaceAll("): WelcomeEmail = ", "): WelcomeEmail =\n    ")
    .replaceAll("> = ", "> =\n    ")
    .replaceAll(".map { ", ".map { signup ->\n        ")
    .replaceAll("() }", "()\n    }")
    .replaceAll(")) }", "))\n    }");
}
