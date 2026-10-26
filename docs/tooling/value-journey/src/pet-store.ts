/** Display names for the fixed fixtures. Keep their source and oracle unchanged. */
export function petStoreNames(text: string): string {
  return text.replace(
    /\b(WireValue|ClientValue|ClientEnvelope|toClientValue|toClientEnvelope|payload)\b/g,
    (name) => {
      switch (name) {
        case "WireValue":
          return "PetRecord";
        case "ClientValue":
          return "Pet";
        case "ClientEnvelope":
          return "PetListing";
        case "toClientValue":
          return "toPet";
        case "toClientEnvelope":
          return "toPetListing";
        case "payload":
          return "pet";
        default:
          return name;
      }
    },
  );
}
