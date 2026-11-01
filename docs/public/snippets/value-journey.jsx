export const ValueJourney = (props) => {
"use strict";
var KastValueJourney = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // src/ValueJourney.tsx
  var ValueJourney_exports = {};
  __export(ValueJourney_exports, {
    ValueJourney: () => ValueJourney
  });

  // <define:KAST_SYNTAX>
  var define_KAST_SYNTAX_default = { E0: [[{ text: "data", start: 0, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 4, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 5, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 10, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 11, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 17, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 18, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 21, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 28, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 34, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 37, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 41, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 42, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 47, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 48, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 56, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 57, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 60, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 67, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 73, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 76, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 87, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 99, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 100, light: "#c12459", dark: "#ff7c9d" }, { text: " recipient: ", start: 103, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 115, light: "#A631BE", dark: "#de8aef" }, { text: ")", start: 123, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "fun", start: 126, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 129, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 130, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 136, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 137, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 155, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 163, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 164, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 170, light: "#693ACF", dark: "#bb99ff" }, { text: "(name ", start: 178, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 184, light: "#006579", dark: "#08C0EF" }, { text: " name)", start: 185, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 192, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 196, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 202, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "draftWelcomeEmail", start: 203, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 220, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 224, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 236, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 237, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 239, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 243, light: "#693ACF", dark: "#bb99ff" }, { text: "(recipient ", start: 255, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 266, light: "#006579", dark: "#08C0EF" }, { text: " ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 268, light: "#693ACF", dark: "#bb99ff" }, { text: "())", start: 282, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 286, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 289, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "prepareEmails", start: 290, light: "#693ACF", dark: "#bb99ff" }, { text: "(signups: ", start: 303, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 313, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 317, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 318, light: "#A631BE", dark: "#de8aef" }, { text: ">): ", start: 324, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 328, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 332, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 333, light: "#A631BE", dark: "#de8aef" }, { text: "> ", start: 345, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 347, light: "#006579", dark: "#08C0EF" }], [{ text: "    signups.", start: 349, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 361, light: "#693ACF", dark: "#bb99ff" }, { text: " { signup ", start: 364, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "->", start: 374, light: "#A631BE", dark: "#de8aef" }], [{ text: "        signup.", start: 377, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "draftWelcomeEmail", start: 392, light: "#693ACF", dark: "#bb99ff" }, { text: "()", start: 409, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "    }", start: 412, light: "#0A0A0A", dark: "#FAFAFA" }], []], E3: [[{ text: "data", start: 0, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 4, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 5, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 10, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 11, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 17, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 18, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 21, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 28, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 34, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 37, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 41, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 42, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 47, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 48, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 56, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 57, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 60, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 67, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 73, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 76, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 87, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 99, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 100, light: "#c12459", dark: "#ff7c9d" }, { text: " recipient: ", start: 103, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 115, light: "#A631BE", dark: "#de8aef" }, { text: ")", start: 123, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "fun", start: 126, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 129, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 130, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 136, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 137, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 155, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 163, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 164, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 170, light: "#693ACF", dark: "#bb99ff" }, { text: "(name ", start: 178, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 184, light: "#006579", dark: "#08C0EF" }, { text: " name)", start: 185, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 192, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 196, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 202, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "draftWelcomeEmail", start: 203, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 220, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 224, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 236, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 237, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 239, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 243, light: "#693ACF", dark: "#bb99ff" }, { text: "(recipient ", start: 255, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 266, light: "#006579", dark: "#08C0EF" }, { text: " ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 268, light: "#693ACF", dark: "#bb99ff" }, { text: "())", start: 282, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 286, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 289, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "prepareCustomers", start: 290, light: "#693ACF", dark: "#bb99ff" }, { text: "(signups: ", start: 306, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 316, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 320, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 321, light: "#A631BE", dark: "#de8aef" }, { text: ">): ", start: 327, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 331, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 335, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 336, light: "#A631BE", dark: "#de8aef" }, { text: "> ", start: 344, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 346, light: "#006579", dark: "#08C0EF" }], [{ text: "    signups.", start: 348, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 360, light: "#693ACF", dark: "#bb99ff" }, { text: " { signup ", start: 363, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "->", start: 373, light: "#A631BE", dark: "#de8aef" }], [{ text: "        signup.", start: 376, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 391, light: "#693ACF", dark: "#bb99ff" }, { text: "()", start: 405, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "    }", start: 408, light: "#0A0A0A", dark: "#FAFAFA" }], []], E4: [[{ text: "data", start: 0, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 4, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 5, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 10, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 11, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 17, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 18, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 21, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 28, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 34, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 37, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 41, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 42, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 47, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 48, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 56, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 57, light: "#c12459", dark: "#ff7c9d" }, { text: " name: ", start: 60, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 67, light: "#A631BE", dark: "#de8aef" }, { text: "?)", start: 73, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "data", start: 76, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 87, light: "#A631BE", dark: "#de8aef" }, { text: "(", start: 99, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 100, light: "#c12459", dark: "#ff7c9d" }, { text: " recipient: ", start: 103, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 115, light: "#A631BE", dark: "#de8aef" }, { text: ")", start: 123, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "fun", start: 126, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 129, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 130, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 136, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 137, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 155, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 163, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 164, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 170, light: "#693ACF", dark: "#bb99ff" }, { text: "(name ", start: 178, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 184, light: "#006579", dark: "#08C0EF" }, { text: " name)", start: 185, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 192, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 196, light: "#A631BE", dark: "#de8aef" }, { text: ".", start: 202, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "draftWelcomeEmail", start: 203, light: "#693ACF", dark: "#bb99ff" }, { text: "(): ", start: 220, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 224, light: "#A631BE", dark: "#de8aef" }, { text: " ", start: 236, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 237, light: "#006579", dark: "#08C0EF" }], [{ text: "    ", start: 239, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 243, light: "#693ACF", dark: "#bb99ff" }, { text: "(recipient ", start: 255, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 266, light: "#006579", dark: "#08C0EF" }, { text: " ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "createCustomer", start: 268, light: "#693ACF", dark: "#bb99ff" }, { text: "())", start: 282, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "fun", start: 286, light: "#c12459", dark: "#ff7c9d" }, { text: " ", start: 289, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "prepareEmails", start: 290, light: "#693ACF", dark: "#bb99ff" }, { text: "(signups: ", start: 303, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 313, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 317, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Signup", start: 318, light: "#A631BE", dark: "#de8aef" }, { text: ">): ", start: 324, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 328, light: "#A631BE", dark: "#de8aef" }, { text: "<", start: 332, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 333, light: "#A631BE", dark: "#de8aef" }, { text: "> ", start: 345, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 347, light: "#006579", dark: "#08C0EF" }], [{ text: "    signups.", start: 349, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 361, light: "#693ACF", dark: "#bb99ff" }, { text: " { signup ", start: 364, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "->", start: 374, light: "#A631BE", dark: "#de8aef" }], [{ text: "        ", start: 377, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "WelcomeEmail", start: 385, light: "#693ACF", dark: "#bb99ff" }, { text: "(", start: 397, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Customer", start: 398, light: "#693ACF", dark: "#bb99ff" }, { text: "(", start: 406, light: "#0A0A0A", dark: "#FAFAFA" }, { text: '"Guest"', start: 407, light: "#147d35", dark: "#5ECC71" }, { text: "))", start: 414, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "    }", start: 417, light: "#0A0A0A", dark: "#FAFAFA" }], []] };

  // ../../../relation/intellij/testData/r1-value-flow/E0.kt?raw
  var E0_default = "package kast.repro\n\ninternal data class WireValue(val id: String?)\ninternal data class ClientValue(val id: String?)\ninternal data class ClientEnvelope(val payload: ClientValue)\n\ninternal fun WireValue.toClientValue(): ClientValue = ClientValue(id = id)\ninternal fun WireValue.toClientEnvelope(): ClientEnvelope = ClientEnvelope(payload = toClientValue())\ninternal fun convert(items: List<WireValue>): List<ClientEnvelope> = items.map { it.toClientEnvelope() }\n";

  // ../../../relation/intellij/testData/r1-value-flow/E3.kt?raw
  var E3_default = "package kast.repro\n\ninternal data class WireValue(val id: String?)\ninternal data class ClientValue(val id: String?)\ninternal data class ClientEnvelope(val payload: ClientValue)\n\ninternal fun WireValue.toClientValue(): ClientValue = ClientValue(id = id)\ninternal fun WireValue.toClientEnvelope(): ClientEnvelope = ClientEnvelope(payload = toClientValue())\ninternal fun convert(items: List<WireValue>): List<ClientValue> = items.map { it.toClientValue() }\n";

  // ../../../relation/intellij/testData/r1-value-flow/E4.kt?raw
  var E4_default = 'package kast.repro\n\ninternal data class WireValue(val id: String?)\ninternal data class ClientValue(val id: String?)\ninternal data class ClientEnvelope(val payload: ClientValue)\n\ninternal fun WireValue.toClientValue(): ClientValue = ClientValue(id = id)\ninternal fun WireValue.toClientEnvelope(): ClientEnvelope = ClientEnvelope(payload = toClientValue())\ninternal fun convert(items: List<WireValue>): List<ClientEnvelope> = items.map { ClientEnvelope(ClientValue("constant")) }\n';

  // ../../../relation/intellij/testData/r1-value-flow/oracle.json
  var oracle_default = {
    created_at: "2026-10-25T03:07:48.589417+00:00",
    authority: "Authored from frozen Kotlin source semantics before IMPACT output. Compiler bindings remain separate necessary evidence.",
    encoding: "UTF-8 LF; anchors count UTF-16 code units; ranges half-open",
    domain: {
      type: "SOURCE_DOMAIN",
      sourceSets: [
        "test"
      ],
      directory: "src/test/kotlin",
      includeSubdirectories: true,
      sourcePolicy: "TEST_ONLY",
      generatedSources: "EXCLUDE"
    },
    variants: [
      {
        id: "E0",
        sha256: "babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2",
        bytes: 460,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload",
          "toClientEnvelope.call.result.payload",
          "callback.RETURN.payload",
          "convert.map.result.Element.payload",
          "convert.RETURN.Element.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E1",
        sha256: "9a7ae785438f45cc6fd1580bedd6b2476dc8018735c8b39414be0bd04ffc08bb",
        bytes: 355,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E2",
        sha256: "6bc8d9364ea7b5d2701157086e4b42eb7647a705f0960732d628dce38a730724",
        bytes: 438,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload",
          "convertOne.toClientEnvelope.call.result.payload",
          "convertOne.RETURN.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E3",
        sha256: "1c712ee3b3fef49f5135a9717c20dd1bff886cb2b4e5dbd1b86a9e98e7e4872b",
        bytes: 454,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "callback.toClientValue.call.result",
          "callback.RETURN",
          "convert.map.result.Element",
          "convert.RETURN.Element"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E4",
        sha256: "4d98aed3d4e9b6c46e1ce5f6cafc350d88d475cd85d2f1fbec3199a11a615035",
        bytes: 478,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility",
          "seed reaches convert returned constant or empty element"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E5",
        sha256: "bcc02012a91a3e651e3d1dc35cd4e19b94abf11004b608679e9ac6ffed00c044",
        bytes: 535,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility",
          "seed reaches convert returned constant or empty element"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E6",
        sha256: "b4b969c5cf9dd73195bc13e7fa348eb62a74da0427b9ac4e7a35075333737223",
        bytes: 455,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload",
          "toClientEnvelope.call.result.payload",
          "callback.RETURN.payload",
          "convert.map.result.Element.payload",
          "convert.RETURN.Element.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      },
      {
        id: "E8",
        sha256: "8a78ed8339ef529c33e723f53f7d39a3c1bf49e751c1a8ab8ac5df7b770217bf",
        bytes: 454,
        seed: {
          file: "src/test/kotlin/kast/repro/Repro.kt",
          start: 232,
          end: 252,
          text: "ClientValue(id = id)"
        },
        expected_path: [
          "seed.ClientValue.result",
          "toClientValue.RETURN",
          "toClientValue.call.result",
          "ClientEnvelope.payload.argument",
          "ClientEnvelope.result.payload",
          "toClientEnvelope.RETURN.payload",
          "toClientEnvelope.call.result.payload",
          "callback.RETURN.payload",
          "supply.body.invoke.result.payload",
          "supply.RETURN.payload",
          "convert.supply.call.result.payload",
          "convert.RETURN.payload"
        ],
        forbidden: [
          "seed result == WireValue or WireValue.id",
          "seed result == whole ClientEnvelope",
          "seed result == whole List",
          "actual callback invocation/count from static possibility"
        ],
        baseline_status: "UNMEASURED diagnostic baseline"
      }
    ],
    minimum_edges: [
      "constructor produced ClientValue flows unchanged into envelope payload",
      "envelope result has payload path for original ClientValue",
      "callback result is envelope with payload path",
      "possible map result element is envelope with payload path",
      "return-to-caller pairs exact call occurrence and owning callable"
    ],
    exclusions: [
      "No actual invocation assertion",
      "No taint/derived edge relabeled exact",
      "No collection-object equality from element flow",
      "No incomplete result proves absence",
      "E7 NOT_APPLICABLE until a dependency model is proposed; unmatched overload remains a negative obligation"
    ]
  };

  // src/display-source.ts
  function displaySource(text, variant) {
    const names = {
      WireValue: "Signup",
      ClientValue: "Customer",
      ClientEnvelope: "WelcomeEmail",
      toClientValue: "createCustomer",
      toClientEnvelope: "draftWelcomeEmail",
      payload: "recipient",
      id: "name",
      items: "signups",
      it: "signup",
      convert: variant === "E3" ? "prepareCustomers" : "prepareEmails"
    };
    return text.replace(
      /\b(WireValue|ClientValue|ClientEnvelope|toClientValue|toClientEnvelope|payload|id|items|it|convert)\b/g,
      (name) => names[name] ?? name
    ).replace("package kast.repro\n\n", "").replaceAll("internal ", "").replaceAll('"constant"', '"Guest"').replaceAll("): Customer = ", "): Customer =\n    ").replaceAll("): WelcomeEmail = ", "): WelcomeEmail =\n    ").replaceAll("> = ", "> =\n    ").replaceAll(".map { ", ".map { signup ->\n        ").replaceAll("() }", "()\n    }").replaceAll(")) }", "))\n    }");
  }

  // src/trace.ts
  var SourceAnchor = class _SourceAnchor {
    constructor(source, start, end) {
      this.source = source;
      this.start = start;
      this.end = end;
    }
    source;
    start;
    end;
    static admit(source, start, text) {
      if (!Number.isInteger(start) || start < 0 || !text || source.slice(start, start + text.length) !== text)
        return { kind: "rejected", code: "INVALID_ANCHOR" };
      return {
        kind: "admitted",
        value: new _SourceAnchor(source, start, start + text.length)
      };
    }
    get text() {
      return this.source.slice(this.start, this.end);
    }
  };
  async function loadExamples() {
    const snapshots = [
      { variant: "E0", source: E0_default },
      { variant: "E3", source: E3_default },
      { variant: "E4", source: E4_default }
    ];
    const digest = async (source) => {
      const bytes = await crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(source)
      );
      return Array.from(
        new Uint8Array(bytes),
        (byte) => byte.toString(16).padStart(2, "0")
      ).join("");
    };
    const digests = await Promise.all([digest(E0_default), digest(E3_default), digest(E4_default)]);
    for (const [index, snapshot] of snapshots.entries()) {
      if (oracle_default.variants.find((variant) => variant.id === snapshot.variant)?.sha256 !== digests[index])
        return { kind: "rejected", code: "SOURCE_CHANGED" };
    }
    const build = (variant, fixtureSource, sha256, fixtureSha256) => {
      const source = displaySource(fixtureSource, variant);
      const anchor = (start, text) => {
        const original = SourceAnchor.admit(fixtureSource, start, text);
        if (original.kind === "rejected") return original;
        return SourceAnchor.admit(
          source,
          displaySource(fixtureSource.slice(0, start), variant).length,
          displaySource(text, variant)
        );
      };
      const seed = anchor(232, "ClientValue(id = id)");
      const call = anchor(338, "toClientValue()");
      const listing = anchor(313, "ClientEnvelope(payload = toClientValue())");
      if (seed.kind === "rejected") return seed;
      if (call.kind === "rejected") return call;
      if (listing.kind === "rejected") return listing;
      const local = { kind: "local" };
      const head = {
        id: "seed.ClientValue.result",
        anchor: seed.value,
        title: "A customer starts here",
        explanation: "createCustomer builds a customer from the signup\u2019s name. Follow this source origin, marked A, through the calls that use it.",
        value: "Customer",
        authority: "compiler",
        operation: "PRODUCER",
        importance: "landmark",
        context: local
      };
      const returned = {
        ...head,
        id: "toClientValue.RETURN",
        title: "Returned by createCustomer",
        explanation: "The function returns the customer to its caller.",
        operation: "SEED_RETURN",
        importance: "detail"
      };
      const wrapper = [
        {
          ...head,
          id: "toClientValue.call.result",
          anchor: call.value,
          title: "This call receives the customer",
          explanation: "The call inside draftWelcomeEmail receives the customer returned by createCustomer. A matching name alone would not establish this connection.",
          operation: "RETURN",
          importance: "normal",
          context: {
            kind: "call",
            title: "Signup.createCustomer",
            anchor: seed.value
          }
        },
        {
          ...head,
          id: "ClientEnvelope.payload.argument",
          anchor: call.value,
          title: "Passed as the recipient",
          explanation: "The WelcomeEmail constructor receives the customer as its recipient argument.",
          operation: "ARGUMENT",
          importance: "detail"
        },
        {
          ...head,
          id: "ClientEnvelope.result.payload",
          anchor: listing.value,
          title: "An email draft holds the customer",
          explanation: "The recipient field holds the customer, not just the customer\u2019s name. The surrounding email draft is a container; it is not another customer.",
          value: "WelcomeEmail.recipient",
          operation: "CONSTRUCT_FIELD",
          importance: "landmark"
        },
        {
          ...head,
          id: "toClientEnvelope.RETURN.payload",
          anchor: listing.value,
          title: "Returned inside an email draft",
          explanation: "draftWelcomeEmail returns a draft whose recipient carries the customer.",
          value: "WelcomeEmail.recipient",
          operation: "SEED_RETURN",
          importance: "detail"
        }
      ];
      const basis = {
        kind: "authored-oracle",
        path: "relation/intellij/testData/r1-value-flow/oracle.json"
      };
      const base = { source, variant, sha256, fixtureSha256, basis };
      const wrapperPath = {
        id: "wrapper",
        label: "Email recipient",
        steps: [head, returned, ...wrapper],
        ending: {
          kind: "expected-end",
          explanation: "This route ends at the returned email\u2019s recipient."
        }
      };
      if (variant === "E4")
        return {
          kind: "ready",
          examples: [
            {
              ...base,
              id: "constant",
              label: "Change one line",
              description: "The list now gets a new Guest customer. The original customer still has another use, but not in this list.",
              paths: [
                {
                  ...wrapperPath,
                  ending: {
                    kind: "excluded",
                    explanation: 'prepareEmails constructs a separate Customer("Guest"). The original customer does not reach this list, even though the list\u2019s declared type stays the same.'
                  }
                }
              ]
            }
          ]
        };
      const callback = anchor(
        variant === "E0" ? 436 : 433,
        variant === "E0" ? "it.toClientEnvelope()" : "it.toClientValue()"
      );
      const map = anchor(
        variant === "E0" ? 424 : 421,
        variant === "E0" ? "items.map { it.toClientEnvelope() }" : "items.map { it.toClientValue() }"
      );
      if (callback.kind === "rejected") return callback;
      if (map.kind === "rejected") return map;
      const value = variant === "E0" ? "WelcomeEmail.recipient" : "Customer";
      const callbackStep = {
        ...head,
        id: variant === "E0" ? "toClientEnvelope.call.result.payload" : "callback.toClientValue.call.result",
        anchor: callback.value,
        title: "The repeated operation receives it",
        explanation: variant === "E0" ? "The operation inside map calls draftWelcomeEmail for a signup and receives the email draft holding the customer." : "This separate call inside map receives a customer directly. It is a different call context from the email recipient route, not one runtime object splitting in two.",
        value,
        operation: "RETURN",
        importance: "normal",
        context: {
          kind: "call",
          title: variant === "E0" ? "Signup.draftWelcomeEmail" : "Signup.createCustomer",
          anchor: variant === "E0" ? listing.value : seed.value
        }
      };
      const callbackReturn = {
        ...callbackStep,
        id: variant === "E0" ? "callback.RETURN.payload" : "callback.RETURN",
        title: "The operation inside map returns it",
        explanation: "The operation inside map returns its result. Connecting that result to a list element needs an additional rule about map; the call alone is not enough.",
        operation: "CALLBACK_RETURN",
        importance: "landmark",
        context: { kind: "callback" }
      };
      const element = {
        ...callbackReturn,
        id: variant === "E0" ? "convert.map.result.Element.payload" : "convert.map.result.Element",
        anchor: map.value,
        title: "The list can hold this result",
        explanation: "The reviewed rule for map connects the callback result to a possible list element.",
        value: `List.Element<${value}>`,
        authority: "reviewed-library",
        operation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT"
      };
      const final = {
        ...element,
        id: variant === "E0" ? "convert.RETURN.Element.payload" : "convert.RETURN.Element",
        title: "The returned list keeps the connection",
        explanation: "The returned list can carry this source origin. That does not tell us whether this program ran, how many elements it produced, or every other place the value is used.",
        authority: "compiler",
        operation: "SEED_RETURN",
        context: local
      };
      const journey = {
        id: "map",
        label: variant === "E0" ? "Email drafts in the list" : "Customers in the list",
        steps: [
          head,
          returned,
          ...variant === "E0" ? wrapper : [],
          callbackStep,
          callbackReturn,
          element,
          final
        ],
        ending: {
          kind: "expected-end",
          explanation: "The route reaches the returned list. The example ends here."
        }
      };
      if (variant === "E3")
        return {
          kind: "ready",
          examples: [
            {
              ...base,
              id: "fork",
              label: "Find the other use",
              description: "Two separate calls use createCustomer: one makes an email recipient; the other returns customers directly in a list.",
              paths: [journey, wrapperPath]
            }
          ]
        };
      return {
        kind: "ready",
        examples: [
          {
            ...base,
            id: "e0",
            label: "Follow a customer",
            description: "A signup becomes a customer, an email draft holds that customer, and a list can hold the draft.",
            paths: [journey]
          },
          {
            ...base,
            id: "gap",
            label: "Remove one rule",
            description: "The source is unchanged. Withhold the map rule and the list connection can no longer be established.",
            paths: [
              {
                ...journey,
                steps: [head, returned, ...wrapper, callbackStep, callbackReturn],
                ending: {
                  kind: "unresolved",
                  obligation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT",
                  explanation: "The callback returns the value. Without the rule for map, this example cannot connect it to a list element."
                }
              }
            ]
          }
        ]
      };
    };
    const positive = build(
      "E0",
      E0_default,
      await digest(displaySource(E0_default, "E0")),
      digests[0]
    );
    const fork = build(
      "E3",
      E3_default,
      await digest(displaySource(E3_default, "E3")),
      digests[1]
    );
    const constant = build(
      "E4",
      E4_default,
      await digest(displaySource(E4_default, "E4")),
      digests[2]
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
        ...constant.examples
      ]
    };
  }
  var AnalysisCursor = class _AnalysisCursor {
    constructor(example, path, step) {
      this.example = example;
      this.path = path;
      this.step = step;
    }
    example;
    path;
    step;
    static start(example) {
      return new _AnalysisCursor(
        example,
        example.paths[0],
        example.paths[0].steps[0]
      );
    }
    get index() {
      return this.path.steps.indexOf(this.step);
    }
    get atEnd() {
      return this.step === this.path.steps.at(-1);
    }
    visible(detail) {
      return this.path.steps.filter(
        (step) => step === this.path.steps.at(-1) || detail === "proof" || step.importance === "landmark" || detail === "normal" && step.importance === "normal"
      );
    }
    move(direction, detail) {
      const candidates = this.visible(detail);
      const next = direction === "next" ? candidates.find((step) => this.path.steps.indexOf(step) > this.index) : candidates.findLast(
        (step) => this.path.steps.indexOf(step) < this.index
      );
      return next ? new _AnalysisCursor(this.example, this.path, next) : this;
    }
    select(path) {
      if (!this.example.paths.includes(path)) return this;
      let shared = 0;
      while (shared <= this.index && path.steps[shared]?.id === this.path.steps[shared]?.id)
        shared++;
      return new _AnalysisCursor(
        this.example,
        path,
        path.steps[Math.max(0, shared - 1)] ?? path.steps[0]
      );
    }
    seek(step) {
      return this.path.steps.includes(step) ? new _AnalysisCursor(this.example, this.path, step) : this;
    }
  };

  // src/ValueJourney.tsx
  function ValueJourney({
    preview = false
  } = {}) {
    const [walkthrough, setWalkthrough] = useState({
      kind: "loading"
    });
    const [focus, setFocus] = useState({ kind: "pinned" });
    const [sourceVisible, setSourceVisible] = useState(true);
    const [sourcePane, setSourcePane] = useState(null);
    const [canvas, setCanvas] = useState(null);
    const [wires, setWires] = useState({ call: "", batch: "", output: "" });
    useEffect(() => {
      let disposed = false;
      async function load() {
        try {
          const result = await loadExamples();
          if (!disposed)
            setWalkthrough(
              result.kind === "ready" ? { ...result, cursor: AnalysisCursor.start(result.examples[0]) } : result
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
      const dismiss = (event) => {
        if (event.key === "Escape") setFocus({ kind: "pinned" });
      };
      document.addEventListener("keydown", dismiss);
      return () => document.removeEventListener("keydown", dismiss);
    }, [focus.kind]);
    useEffect(() => {
      if (!sourcePane || !sourceVisible) return;
      function reveal() {
        if (!sourcePane) return;
        const active = sourcePane.querySelector(
          '.vj-source-line[data-active="true"]'
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
    const exampleId = walkthrough.kind === "ready" ? walkthrough.cursor.example.id : "loading";
    const routeId = walkthrough.kind === "ready" ? walkthrough.cursor.path.id : "loading";
    useEffect(() => {
      if (!canvas) return;
      const producer = canvas.querySelector(
        '[data-vj-node="producer"]'
      );
      const draft = canvas.querySelector('[data-vj-node="draft"]');
      const batch = canvas.querySelector('[data-vj-node="batch"]');
      const output = canvas.querySelector('[data-vj-node="output"]');
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
        const fromX = (direct ? a.left + a.width / 2 : b.left + b.width / 2) - root.left;
        const fromY = (direct ? a.bottom : b.bottom) - root.top;
        const toX = c.left + c.width / 2 - root.left;
        const toY = c.top - root.top;
        const containerSource = direct && routeId === "wrapper" ? b : c;
        const right = d.left > containerSource.right;
        const outputFromX = (right ? containerSource.right : containerSource.left + containerSource.width / 2) - root.left;
        const outputFromY = (right ? containerSource.top + containerSource.height / 2 : containerSource.bottom) - root.top;
        const outputToX = (right ? d.left : d.left + d.width / 2) - root.left;
        const outputToY = (right ? d.top + d.height / 2 : d.top) - root.top;
        setWires({
          call: `M${x1},${y1} C${x1 + 28},${y1} ${x2 - 28},${y2} ${x2},${y2}`,
          batch: `M${fromX},${fromY} C${fromX},${fromY + 20} ${toX},${toY - 20} ${toX},${toY}`,
          output: right ? `M${outputFromX},${outputFromY} C${outputFromX + 28},${outputFromY} ${outputToX - 28},${outputToY} ${outputToX},${outputToY}` : `M${outputFromX},${outputFromY} C${outputFromX},${outputFromY + 20} ${outputToX},${outputToY - 20} ${outputToX},${outputToY}`
        });
      }
      draw();
      const observer = new ResizeObserver(draw);
      for (const node of [canvas, producer, draft, batch, output])
        observer.observe(node);
      return () => observer.disconnect();
    }, [canvas, exampleId, routeId, sourceVisible]);
    if (walkthrough.kind === "loading")
      return <div className="value-journey vj-loading not-prose" role="status">
        Loading the source example…
      </div>;
    if (walkthrough.kind === "rejected")
      return <div className="value-journey vj-loading not-prose" role="alert">
        The example is unavailable ({walkthrough.code}).
      </div>;
    const { cursor, examples } = walkthrough;
    const { example, path, step } = cursor;
    const frames = path.steps.filter(
      (candidate) => candidate.operation === "PRODUCER" || candidate.id === "toClientValue.call.result" || candidate.operation === "CONSTRUCT_FIELD" || candidate.operation === "CALLBACK_RETURN" || candidate.authority === "reviewed-library"
    );
    const position = Math.max(
      0,
      frames.findLastIndex(
        (candidate) => path.steps.indexOf(candidate) <= cursor.index
      )
    );
    const focused = focus.kind === "preview" ? focus.step : step;
    const lineOf = (anchor) => example.source.slice(0, anchor.start).split("\n").length - 1;
    const origin = path.steps[0];
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
      (candidate) => candidate.operation === "CONSTRUCT_FIELD"
    );
    const batchFrame = frames.find(
      (candidate) => candidate.operation === "CALLBACK_RETURN"
    );
    const outputFrame = gap || different ? void 0 : frames.find(
      (candidate) => candidate.authority === "reviewed-library"
    ) ?? draftFrame;
    function navigate(next) {
      setWalkthrough({ kind: "ready", examples, cursor: next });
      setFocus({ kind: "pinned" });
    }
    function chooseExample(id) {
      const target = examples.find((candidate) => candidate.id === id);
      if (target) navigate(AnalysisCursor.start(target));
    }
    function pick(candidate) {
      return {
        onPointerEnter: () => setFocus({ kind: "preview", step: candidate }),
        onPointerLeave: () => setFocus({ kind: "pinned" }),
        onFocus: () => setFocus({ kind: "preview", step: candidate }),
        onBlur: () => setFocus({ kind: "pinned" }),
        onClick: () => navigate(cursor.seek(candidate)),
        onKeyDown: (event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            navigate(cursor.seek(candidate));
          }
        }
      };
    }
    function sourceLine(index, interactive, indent) {
      const tokens = define_KAST_SYNTAX_default[example.variant][index] ?? [];
      const first = tokens[0];
      if (!first) return [];
      const start = first.start;
      const end = start + (lines[index]?.length ?? 0);
      const signatureTail = lines[index]?.match(/\S+ =$/u)?.[0];
      const tailStart = end - (signatureTail?.length ?? 0);
      const boundaries = [
        .../* @__PURE__ */ new Set([
          start,
          start + indent,
          tailStart,
          end,
          ...frames.flatMap((candidate) => [
            candidate.anchor.start,
            candidate.anchor.end
          ]).filter((offset) => offset > start && offset < end)
        ])
      ].sort((a, b) => a - b);
      return boundaries.slice(0, -1).map((from, index2) => {
        const to = boundaries[index2 + 1] ?? end;
        const candidate = frames.filter((frame) => frame.anchor.start <= from && frame.anchor.end >= to).sort(
          (a, b) => a.anchor.end - a.anchor.start - (b.anchor.end - b.anchor.start)
        )[0];
        const content = tokens.filter(
          (token) => token.start < to && token.start + token.text.length > from
        ).map((token) => <span
          key={token.start}
          style={{ color: `light-dark(${token.light}, ${token.dark})` }}
        >
            {token.text.slice(
          Math.max(0, from - token.start),
          Math.min(token.text.length, to - token.start)
        )}
          </span>);
        const selected = example.source.slice(from, to).trim().length > 0 && (focused.anchor.start <= from && focused.anchor.end >= to || focused.context.kind === "call" && focused.context.anchor.start <= from && focused.context.anchor.end >= to);
        return interactive && candidate && /[\p{L}\p{N}]/u.test(example.source.slice(from, to)) ? <span
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
        </span> : <span
          key={from}
          className={from === start && to === start + indent ? "vj-code-indent" : from === tailStart ? "vj-code-tail" : selected && !preview ? "vj-code-selected" : void 0}
        >
          {content}
        </span>;
      });
    }
    function excerpt(from, to, interactive = true) {
      return <div className="vj-code">
        {lines.slice(from, to).map((text, index) => {
        const indent = text.length - text.trimStart().length;
        const offset = lines.slice(0, from + index).join("\n").length + (from + index > 0 ? 1 : 0);
        const active = focused.anchor.start < offset + text.length && focused.anchor.end > offset;
        return <div
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
            textIndent: "-2ch"
          }}
        >
                {text ? sourceLine(from + index, interactive, indent) : <br />}
              </code>
            </div>;
      })}
      </div>;
    }
    function customer(other = false) {
      return <span className={other ? "vj-customer vj-other" : "vj-customer"}>
        <span className="vj-token" aria-hidden="true">
          {other ? "B" : "A"}
        </span>
        <span>
          <strong>{other ? "A different customer" : "Customer"}</strong>
          <small>{other ? 'Customer("Guest")' : "from createCustomer"}</small>
        </span>
      </span>;
    }
    function containment() {
      return <div
        className={gap ? "vj-containment vj-uncertain" : "vj-containment"}
        data-vj-node="output"
        role={!preview && outputFrame ? "button" : void 0}
        tabIndex={!preview && outputFrame ? 0 : void 0}
        aria-label={!preview && outputFrame ? outputFrame.title : void 0}
        aria-pressed={!preview && outputFrame ? step === outputFrame : void 0}
        {...!preview && outputFrame ? pick(outputFrame) : {}}
      >
        <div className="vj-container-label">
          {onEmailRoute ? "Returned email draft" : fork ? "Returned customer list" : "Returned email list"}
        </div>
        {gap ? <div className="vj-unknown">
            <strong>?</strong>
            <b>Connection not established</b>
            <p>The source is unchanged. The map rule is missing.</p>
          </div> : fork && !onEmailRoute ? customer() : <div className="vj-email">
            <span>WelcomeEmail</span>
            <div className="vj-recipient">
              <small>recipient holds</small>
              {customer(different)}
            </div>
          </div>}
      </div>;
    }
    if (preview)
      return <div className="value-journey vj-overview not-prose">
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
      </div>;
    return <div
      className="value-journey vj-explorer not-prose"
      data-example={example.id}
      data-step={step.id}
      data-preview={focus.kind === "preview"}
    >
      <div className="vj-cases" role="group" aria-label="Prepared example">
        {["e0", "constant", "fork", "gap"].map((id) => {
      const candidate = examples.find((item) => item.id === id);
      return candidate ? <button
        key={id}
        type="button"
        aria-pressed={example.id === id}
        onClick={() => chooseExample(id)}
      >
              {candidate.label}
            </button> : null;
    })}
      </div>
      <p className="vj-context" role="status">
        {example.description}
      </p>
      {fork && <div
      className="vj-route-choice"
      role="group"
      aria-label="Choose a call context"
    >
          <span>Follow a call:</span>
          {example.paths.map((route) => <button
      key={route.id}
      type="button"
      aria-pressed={path === route}
      onClick={() => navigate(cursor.select(route))}
    >
              {route.label} ↗
            </button>)}
        </div>}

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
      data-focus={focused.authority === "reviewed-library" ? "output" : lineOf(focused.anchor) < draftStart ? "producer" : lineOf(focused.anchor) < batchStart ? "draft" : "batch"}
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
      className={onEmailRoute ? "vj-wire" : "vj-wire vj-wire-output"}
      markerEnd={gap ? void 0 : onEmailRoute ? "url(#vj-source-arrow)" : "url(#vj-library-arrow)"}
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
      role={draftFrame ? "button" : void 0}
      tabIndex={draftFrame ? 0 : void 0}
      aria-label="Hold the customer in an email draft"
      aria-pressed={draftFrame ? step === draftFrame : void 0}
      {...draftFrame ? pick(draftFrame) : {}}
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
      role={batchFrame ? "button" : void 0}
      tabIndex={batchFrame ? 0 : void 0}
      aria-label={fork ? "Collect the customers" : "Collect the email drafts"}
      aria-pressed={batchFrame ? step === batchFrame : void 0}
      {...batchFrame ? pick(batchFrame) : {}}
    >
                <div className="vj-node-head">
                  <span>
                    {fork ? "Collect the customers" : "Collect the email drafts"}
                  </span>
                </div>
                {excerpt(batchStart + 1, lines.length, false)}
                <div className="vj-node-foot">
                  {different ? customer(true) : gap ? <span className="vj-warning">
                      ? The list connection needs the map rule
                    </span> : <span className="vj-mini-email">
                      {fork ? "List element" : "Draft recipient"}
                      <span>
                        → <b>A</b>
                      </span>
                    </span>}
                  <p>
                    {different ? "The same list type. A different source origin." : fork ? "A separate call context, not a runtime split." : "map repeats the operation for each signup."}
                  </p>
                </div>
              </section>
              <section className="vj-structure" aria-label="Returned value">
                {containment()}
                <p>
                  {different ? 'This list holds drafts addressed to Customer("Guest"), not customer A.' : gap ? "The email\u2019s recipient is connected to A. Its place in the returned list is not established." : onEmailRoute ? "draftWelcomeEmail returns a draft with customer A as its recipient." : fork ? "prepareCustomers can return customer A directly as a list element." : "prepareEmails can return an email draft whose recipient is customer A."}
                </p>
                <small className="vj-transfer-basis">
                  {onEmailRoute ? "Source binding" : gap ? "Missing support: map \u2192 list element" : "List connection: reviewed map rule"}
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
            {focus.kind === "preview" ? `Preview: ${focused.title}` : "Highlights follow your selection, not execution."}
          </span>
        </div>

        <section
      className={stopped ? "vj-inspector vj-inspector-gap" : "vj-inspector"}
      aria-label="Selected connection"
    >
          <div className="vj-explanation">
            <h3>
              {stopped ? "The explanation stops before the list." : atEnd && different ? "This list gets a different customer." : step.title}
            </h3>
            <p>
              {atEnd && path.ending.kind !== "expected-end" ? path.ending.explanation : step.explanation}
            </p>
            <p className="vj-support">
              {stopped ? "Missing support: map \u2192 list element." : step.authority === "reviewed-library" ? "Support: reviewed map rule, not the call binding alone." : "Support: source binding, not matching names."}
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
        {frames.map((candidate, index) => <button
      type="button"
      key={candidate.id}
      aria-current={candidate === step ? "step" : void 0}
      {...pick(candidate)}
    >
            <span>{String(index + 1).padStart(2, "0")}</span>
            {candidate.title}
          </button>)}
      </nav>

      <p className="vj-reading-note">
        {different ? "Changing the returned expression changes which customer the list can hold. The original customer\u2019s email-recipient route still exists elsewhere in this example." : gap ? "The call and field connections remain. Without support for the library step, this illustration stops rather than guessing that the list carries the customer." : fork ? "One function can be called from different places. Each route keeps its own call context; this picture does not show one object being shared between them." : "A call receives a result. A field holds a value. The reviewed map rule connects the operation\u2019s result to a possible list element. Select a connection to see its source lines."}
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
              {path.steps.map((candidate) => <li key={candidate.id}>
                  <strong>{candidate.title}</strong>
                  <span>
                    {candidate.authority === "compiler" ? "Source binding" : "Reviewed library transfer"}{" "}
                    · {candidate.operation}
                  </span>
                  <code>{candidate.anchor.text}</code>
                </li>)}
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
    </div>;
  }
  return __toCommonJS(ValueJourney_exports);
})();
return KastValueJourney.ValueJourney(props); };
