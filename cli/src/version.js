// @ts-check
import { readFileSync } from "node:fs";

/** This package's version, read once from package.json. */
export const version = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")).version;
