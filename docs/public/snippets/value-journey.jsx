export const ValueJourney = () => {
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
  var define_KAST_SYNTAX_default = { E0: [[{ text: "package", start: 0, light: "#D32A61", dark: "#FF678D" }, { text: " kast.repro", start: 7, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 20, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 28, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 29, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 33, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 34, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 39, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 40, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 49, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 50, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 53, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 58, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 64, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 67, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 75, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 76, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 87, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 90, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 91, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 94, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 99, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 105, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 108, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 116, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 117, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 121, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 122, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 127, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 128, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 138, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 139, light: "#D32A61", dark: "#FF678D" }, { text: " pet: ", start: 142, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 148, light: "#A631BE", dark: "#D568EA" }, { text: ")", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 154, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 162, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 163, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 167, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 176, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 177, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 182, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 186, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 189, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 190, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 191, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 192, light: "#693ACF", dark: "#9D6AFB" }, { text: "(id ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 199, light: "#08C0EF", dark: "#08C0EF" }, { text: " id)", start: 200, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 205, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 213, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 214, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 217, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 218, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 227, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPetListing", start: 228, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 240, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 244, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 254, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 255, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 256, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 257, light: "#693ACF", dark: "#9D6AFB" }, { text: "(pet ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 272, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 273, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 274, light: "#693ACF", dark: "#9D6AFB" }, { text: "())", start: 279, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 283, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 291, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 292, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 295, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "convert", start: 296, light: "#693ACF", dark: "#9D6AFB" }, { text: "(items: ", start: 303, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 311, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 315, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 316, light: "#A631BE", dark: "#D568EA" }, { text: ">): ", start: 325, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 329, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 333, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 334, light: "#A631BE", dark: "#D568EA" }, { text: "> ", start: 344, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 346, light: "#08C0EF", dark: "#08C0EF" }, { text: " items.", start: 347, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 354, light: "#693ACF", dark: "#9D6AFB" }, { text: " { it.", start: 357, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPetListing", start: 363, light: "#693ACF", dark: "#9D6AFB" }, { text: "() }", start: 375, light: "#0A0A0A", dark: "#FAFAFA" }], []], E3: [[{ text: "package", start: 0, light: "#D32A61", dark: "#FF678D" }, { text: " kast.repro", start: 7, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 20, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 28, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 29, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 33, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 34, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 39, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 40, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 49, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 50, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 53, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 58, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 64, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 67, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 75, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 76, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 87, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 90, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 91, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 94, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 99, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 105, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 108, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 116, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 117, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 121, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 122, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 127, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 128, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 138, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 139, light: "#D32A61", dark: "#FF678D" }, { text: " pet: ", start: 142, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 148, light: "#A631BE", dark: "#D568EA" }, { text: ")", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 154, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 162, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 163, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 167, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 176, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 177, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 182, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 186, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 189, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 190, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 191, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 192, light: "#693ACF", dark: "#9D6AFB" }, { text: "(id ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 199, light: "#08C0EF", dark: "#08C0EF" }, { text: " id)", start: 200, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 205, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 213, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 214, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 217, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 218, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 227, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPetListing", start: 228, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 240, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 244, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 254, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 255, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 256, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 257, light: "#693ACF", dark: "#9D6AFB" }, { text: "(pet ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 272, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 273, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 274, light: "#693ACF", dark: "#9D6AFB" }, { text: "())", start: 279, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 283, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 291, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 292, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 295, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "convert", start: 296, light: "#693ACF", dark: "#9D6AFB" }, { text: "(items: ", start: 303, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 311, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 315, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 316, light: "#A631BE", dark: "#D568EA" }, { text: ">): ", start: 325, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 329, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 333, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 334, light: "#A631BE", dark: "#D568EA" }, { text: "> ", start: 337, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 339, light: "#08C0EF", dark: "#08C0EF" }, { text: " items.", start: 340, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 347, light: "#693ACF", dark: "#9D6AFB" }, { text: " { it.", start: 350, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 356, light: "#693ACF", dark: "#9D6AFB" }, { text: "() }", start: 361, light: "#0A0A0A", dark: "#FAFAFA" }], []], E4: [[{ text: "package", start: 0, light: "#D32A61", dark: "#FF678D" }, { text: " kast.repro", start: 7, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 20, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 28, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 29, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 33, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 34, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 39, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 40, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 49, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 50, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 53, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 58, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 64, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 67, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 75, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 76, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 80, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 81, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 86, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 87, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 90, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 91, light: "#D32A61", dark: "#FF678D" }, { text: " id: ", start: 94, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "String", start: 99, light: "#A631BE", dark: "#D568EA" }, { text: "?)", start: 105, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 108, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 116, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "data", start: 117, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 121, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "class", start: 122, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 127, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 128, light: "#A631BE", dark: "#D568EA" }, { text: "(", start: 138, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "val", start: 139, light: "#D32A61", dark: "#FF678D" }, { text: " pet: ", start: 142, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 148, light: "#A631BE", dark: "#D568EA" }, { text: ")", start: 151, light: "#0A0A0A", dark: "#FAFAFA" }], [], [{ text: "internal", start: 154, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 162, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 163, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 166, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 167, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 176, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 177, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 182, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 186, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 189, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 190, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 191, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 192, light: "#693ACF", dark: "#9D6AFB" }, { text: "(id ", start: 195, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 199, light: "#08C0EF", dark: "#08C0EF" }, { text: " id)", start: 200, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 205, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 213, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 214, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 217, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 218, light: "#A631BE", dark: "#D568EA" }, { text: ".", start: 227, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPetListing", start: 228, light: "#693ACF", dark: "#9D6AFB" }, { text: "(): ", start: 240, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 244, light: "#A631BE", dark: "#D568EA" }, { text: " ", start: 254, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 255, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 256, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 257, light: "#693ACF", dark: "#9D6AFB" }, { text: "(pet ", start: 267, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 272, light: "#08C0EF", dark: "#08C0EF" }, { text: " ", start: 273, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "toPet", start: 274, light: "#693ACF", dark: "#9D6AFB" }, { text: "())", start: 279, light: "#0A0A0A", dark: "#FAFAFA" }], [{ text: "internal", start: 283, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 291, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "fun", start: 292, light: "#D32A61", dark: "#FF678D" }, { text: " ", start: 295, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "convert", start: 296, light: "#693ACF", dark: "#9D6AFB" }, { text: "(items: ", start: 303, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 311, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 315, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetRecord", start: 316, light: "#A631BE", dark: "#D568EA" }, { text: ">): ", start: 325, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "List", start: 329, light: "#A631BE", dark: "#D568EA" }, { text: "<", start: 333, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 334, light: "#A631BE", dark: "#D568EA" }, { text: "> ", start: 344, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "=", start: 346, light: "#08C0EF", dark: "#08C0EF" }, { text: " items.", start: 347, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "map", start: 354, light: "#693ACF", dark: "#9D6AFB" }, { text: " { ", start: 357, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "PetListing", start: 360, light: "#693ACF", dark: "#9D6AFB" }, { text: "(", start: 370, light: "#0A0A0A", dark: "#FAFAFA" }, { text: "Pet", start: 371, light: "#693ACF", dark: "#9D6AFB" }, { text: "(", start: 374, light: "#0A0A0A", dark: "#FAFAFA" }, { text: '"constant"', start: 375, light: "#147d35", dark: "#5ECC71" }, { text: ")) }", start: 385, light: "#0A0A0A", dark: "#FAFAFA" }], []] };

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

  // src/pet-store.ts
  function petStoreNames(text) {
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
      }
    );
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
      const source = petStoreNames(fixtureSource);
      const anchor = (start, text) => {
        const original = SourceAnchor.admit(fixtureSource, start, text);
        if (original.kind === "rejected") return original;
        return SourceAnchor.admit(
          source,
          petStoreNames(fixtureSource.slice(0, start)).length,
          petStoreNames(text)
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
        title: "Start with this value",
        explanation: "This constructor creates the Pet that we will trace.",
        value: "Pet",
        authority: "compiler",
        operation: "PRODUCER",
        importance: "landmark",
        context: local
      };
      const returned = {
        ...head,
        id: "toClientValue.RETURN",
        title: "Returned by toPet",
        explanation: "The helper returns this Pet to its caller.",
        operation: "SEED_RETURN",
        importance: "detail"
      };
      const wrapper = [
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
            anchor: seed.value
          }
        },
        {
          ...head,
          id: "ClientEnvelope.payload.argument",
          anchor: call.value,
          title: "Passed as pet",
          explanation: "The constructor receives the value as its pet argument.",
          operation: "ARGUMENT",
          importance: "detail"
        },
        {
          ...head,
          id: "ClientEnvelope.result.payload",
          anchor: listing.value,
          title: "Stored in the pet field",
          explanation: "The object carries our value in its pet field.",
          value: "PetListing.pet",
          operation: "CONSTRUCT_FIELD",
          importance: "landmark"
        },
        {
          ...head,
          id: "toClientEnvelope.RETURN.payload",
          anchor: listing.value,
          title: "Returned inside a listing",
          explanation: "toPetListing returns a listing whose pet carries the same Pet.",
          value: "PetListing.pet",
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
        label: "Listing pet",
        steps: [head, returned, ...wrapper],
        ending: {
          kind: "expected-end",
          explanation: "This example ends at the returned pet."
        }
      };
      if (variant === "E4")
        return {
          kind: "ready",
          examples: [
            {
              ...base,
              id: "constant",
              label: "Return a different value",
              description: "A different value has the same type. Does the original still reach the list?",
              paths: [
                {
                  ...wrapperPath,
                  ending: {
                    kind: "excluded",
                    explanation: 'convert constructs a separate Pet("constant"). The original value does not reach this list.'
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
      const pet = variant === "E0" ? "PetListing.pet" : "Pet";
      const callbackStep = {
        ...head,
        id: variant === "E0" ? "toClientEnvelope.call.result.payload" : "callback.toClientValue.call.result",
        anchor: callback.value,
        title: "Returned to the callback",
        explanation: `The helper returns ${pet} inside the map callback.`,
        value: pet,
        operation: "RETURN",
        importance: "normal",
        context: {
          kind: "call",
          title: variant === "E0" ? "PetRecord.toPetListing" : "PetRecord.toPet",
          anchor: variant === "E0" ? listing.value : seed.value
        }
      };
      const callbackReturn = {
        ...callbackStep,
        id: variant === "E0" ? "callback.RETURN.payload" : "callback.RETURN",
        title: "The callback returns it",
        explanation: "The callback returns our value inside its result. The next step needs the rule for map.",
        operation: "CALLBACK_RETURN",
        importance: "landmark",
        context: { kind: "callback" }
      };
      const element = {
        ...callbackReturn,
        id: variant === "E0" ? "convert.map.result.Element.payload" : "convert.map.result.Element",
        anchor: map.value,
        title: "Into a list element",
        explanation: "The reviewed rule for map connects the callback result to a possible list element.",
        value: `List.Element<${pet}>`,
        authority: "reviewed-library",
        operation: "CALLBACK_RESULT_TO_OUTPUT_ELEMENT"
      };
      const final = {
        ...element,
        id: variant === "E0" ? "convert.RETURN.Element.payload" : "convert.RETURN.Element",
        title: "Returned from convert",
        explanation: "A returned list element can carry our original value.",
        authority: "compiler",
        operation: "SEED_RETURN",
        context: local
      };
      const journey = {
        id: "map",
        label: variant === "E0" ? "Listing \u2192 map element" : "Direct \u2192 map element",
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
              label: "Use the value twice",
              description: "One producer has two uses. Choose a route to see where each leads.",
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
            label: "Follow the value",
            description: "Where does this value go after the helper returns it?",
            paths: [journey]
          },
          {
            ...base,
            id: "gap",
            label: "Stop at a missing connection",
            description: "The code stays the same. This illustration removes the rule for map.",
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
    const positive = build("E0", E0_default, await digest(petStoreNames(E0_default)), digests[0]);
    const fork = build("E3", E3_default, await digest(petStoreNames(E3_default)), digests[1]);
    const constant = build("E4", E4_default, await digest(petStoreNames(E4_default)), digests[2]);
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
  function ValueJourney() {
    const [walkthrough, setWalkthrough] = useState({
      kind: "loading"
    });
    const [detail, setDetail] = useState("story");
    const [portal, setPortal] = useState(false);
    const [proof, setProof] = useState(false);
    useEffect(() => {
      let disposed = false;
      async function load() {
        try {
          const result = await loadExamples();
          if (disposed) return;
          setWalkthrough(
            result.kind === "ready" ? { ...result, cursor: AnalysisCursor.start(result.examples[0]) } : result
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
      return <div className="value-journey vj-loading not-prose">
        The example loads here…
      </div>;
    if (walkthrough.kind === "rejected")
      return <div className="value-journey vj-loading not-prose" role="alert">
        The example is unavailable ({walkthrough.code}).
      </div>;
    const { cursor, examples } = walkthrough;
    const { example, path, step } = cursor;
    const visible = cursor.visible(detail);
    const line = example.source.slice(0, step.anchor.start).split("\n").length;
    const stopped = cursor.atEnd && path.ending.kind === "unresolved";
    const authority = step.authority === "compiler" ? "\u25C6 Compiler" : "\u25C7 Reviewed library";
    const lines = example.source.trimEnd().split("\n");
    function navigate(next) {
      setWalkthrough({ kind: "ready", examples, cursor: next });
      setPortal(false);
      setProof(false);
    }
    function chooseExample(id, compare = false) {
      const target = examples.find((candidate) => candidate.id === id);
      if (!target) return;
      let next = AnalysisCursor.start(target);
      if (compare) {
        for (const candidate of next.path.steps) {
          if (path.steps.some(
            (old, index) => index <= cursor.index && old.id === candidate.id
          ))
            next = next.seek(candidate);
        }
      }
      navigate(next);
    }
    function sourceLine(index, highlight = true) {
      return define_KAST_SYNTAX_default[example.variant][index]?.map((token) => {
        const from = Math.max(0, step.anchor.start - token.start);
        const to = Math.min(token.text.length, step.anchor.end - token.start);
        return <span
          key={token.start}
          style={{ color: `light-dark(${token.light}, ${token.dark})` }}
        >
          {highlight && from < to ? <>
              {token.text.slice(0, from)}
              <mark>{token.text.slice(from, to)}</mark>
              {token.text.slice(to)}
            </> : token.text}
        </span>;
      });
    }
    function progressLabel(candidate) {
      return `Step ${path.steps.indexOf(candidate) + 1}: ${candidate.title}`;
    }
    return <div
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
          (candidate) => candidate.id === event.target.value
        );
        if (target) chooseExample(target.id);
      }}
    >
            {examples.map((candidate) => <option key={candidate.id} value={candidate.id}>
                {candidate.label}
              </option>)}
          </select>
        </label>
        <div className="vj-detail" role="group" aria-label="Explanation detail">
          {["story", "normal", "proof"].map((level) => <button
      key={{ story: "Overview", normal: "Every step", proof: "Evidence" }[level]}
      type="button"
      aria-pressed={detail === level}
      onClick={() => setDetail(level)}
    >
              {{ story: "Overview", normal: "Every step", proof: "Evidence" }[level]}
            </button>)}
        </div>
      </div>

      <p className="vj-question">{example.description}</p>
      <nav className="vj-route" aria-label="Value route">
        {cursor.visible("story").map((candidate) => <button
      key={candidate.id}
      type="button"
      aria-current={candidate === step ? "step" : void 0}
      data-reached={path.steps.indexOf(candidate) <= cursor.index}
      onClick={() => navigate(cursor.seek(candidate))}
    >
            <span aria-hidden="true">●</span>
            <span>{candidate.title}</span>
          </button>)}
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
      return <div key={index}>
              <div
        className={index + 1 === line ? "vj-line vj-line-active" : "vj-line"}
      >
                <span className="vj-line-number" aria-hidden="true">
                  {index + 1}
                </span>
                <code>{text ? sourceLine(index) : "\xA0"}</code>
              </div>
              {index + 1 === line && <div className={stopped ? "vj-inline vj-gap" : "vj-inline"}>
                  <div className="vj-value">
                    <span aria-hidden="true">●</span> {step.value}
                    <span className="vj-value-label">tracked value</span>
                  </div>
                  <div className="vj-explanation">
                    {stopped && <span className="vj-eyebrow">Missing connection</span>}
                    <div className="vj-step-heading">
                      <h3>
                        {stopped ? "The explanation stops here." : step.title}
                      </h3>
                      <span className={`vj-authority vj-${step.authority}`}>
                        {authority} · illustration
                      </span>
                    </div>
                    <p>{step.explanation}</p>

                    {step.context.kind === "call" && <>
                        <button
        className="vj-disclosure"
        type="button"
        aria-expanded={portal}
        onClick={() => setPortal(!portal)}
      >
                          {portal ? "\u2212" : "+"} Peek {step.context.title}
                        </button>
                        {portal && <div className="vj-portal">
                            <span className="vj-eyebrow">
                              Originating function · same source snapshot
                            </span>
                            <pre>
                              <code>
                                {sourceLine(
        step.context.anchor.source.slice(0, step.context.anchor.start).split("\n").length - 1,
        false
      )}
                              </code>
                            </pre>
                            <p>
                              Return → exact call on line {line}. The call site
                              stays in view.
                            </p>
                          </div>}
                      </>}

                    {step.context.kind === "callback" && (proof || detail === "proof") && <div className="vj-callback">
                          <div>
                            <span className="vj-eyebrow">Callback frame</span>
                            <code>map.transform</code>
                          </div>
                          {(proof || detail === "proof") && <dl>
                              <dt>Supplier</dt>
                              <dd>convert</dd>
                              <dt>Anonymous owner</dt>
                              <dd>lambda on line 9</dd>
                              <dt>Result carries</dt>
                              <dd>
                                <code>
                                  {example.variant === "E3" ? "Pet" : "PetListing.pet"}
                                </code>
                              </dd>
                            </dl>}
                          <div className="vj-proof-split">
                            <span>◆ Bind lambda to transform</span>
                            <span>Compiler · expected</span>
                          </div>
                          <div
        className={stopped ? "vj-proof-split vj-missing" : "vj-proof-split"}
      >
                            <span>◇ Callback result → output element</span>
                            <span>
                              {stopped ? "Missing proof" : step.authority === "reviewed-library" ? "Reviewed rule \xB7 expected" : "Next expected transfer"}
                            </span>
                          </div>
                        </div>}

                    {cursor.atEnd && <div className="vj-ending">
                        <strong>
                          {path.ending.kind === "unresolved" ? "The library connection needs evidence" : path.ending.kind === "excluded" ? "The list gets a different value" : "This route ends here"}
                        </strong>
                        <p>{path.ending.explanation}</p>
                      </div>}

                    {example.paths.length > 1 && cursor.index <= 1 && <div className="vj-fork">
                        <span className="vj-eyebrow">Choose a route</span>
                        {example.paths.map((alternative) => <button
        type="button"
        key={alternative.id}
        aria-pressed={path === alternative}
        onClick={() => navigate(cursor.select(alternative))}
      >
                            <span aria-hidden="true">↳</span>{" "}
                            {alternative.label}
                          </button>)}
                        <p>These routes describe possible uses in source.</p>
                      </div>}

                    {detail !== "proof" && <button
        className="vj-disclosure"
        type="button"
        aria-expanded={proof}
        onClick={() => setProof(!proof)}
      >
                        {proof ? "\u2212" : "+"} Show the evidence
                      </button>}
                    {(proof || detail === "proof") && <dl className="vj-proof-details">
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
                      </dl>}

                    {(stopped || example.id === "e0" && (cursor.atEnd || step.authority === "reviewed-library")) && <button
        type="button"
        className="vj-compare"
        onClick={() => chooseExample(stopped ? "e0" : "gap", true)}
      >
                        {stopped ? "Restore the connection" : "What if a connection is missing?"}{" "}
                        <span aria-hidden="true">↗</span>
                      </button>}
                  </div>
                </div>}
            </div>;
    })}
      </section>

      <div className="vj-controls">
        <div className="vj-position" role="status">
          <span className="vj-eyebrow">Step</span>
          {cursor.index + 1} / {path.steps.length}
        </div>
        <nav className="vj-progress" aria-label="Value journey steps">
          {path.steps.filter(
      (candidate) => candidate === step || visible.includes(candidate)
    ).map((candidate) => <button
      type="button"
      key={candidate.id}
      aria-label={progressLabel(candidate)}
      aria-current={candidate === step ? "step" : void 0}
      title={progressLabel(candidate)}
      onClick={() => navigate(cursor.seek(candidate))}
    >
                {String(path.steps.indexOf(candidate) + 1).padStart(2, "0")}
              </button>)}
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
    </div>;
  }
  return __toCommonJS(ValueJourney_exports);
})();
return KastValueJourney.ValueJourney(); };
