// Mintlify injects these hooks into its JSX snippet sandbox; no runtime imports are needed.
declare const useState: typeof import("react").useState;
declare const useEffect: typeof import("react").useEffect;

interface SyntaxToken {
  readonly text: string;
  readonly start: number;
  readonly light: string;
  readonly dark: string;
}

// The build embeds Shiki's source-preserving tokens for both Pierre themes.
declare const KAST_SYNTAX: Readonly<
  Record<"E0" | "E3" | "E4", readonly (readonly SyntaxToken[])[]>
>;
