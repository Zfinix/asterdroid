#!/usr/bin/env node
import { main } from "../src/main.js";

const code = await main(process.argv.slice(2));
// stdin may still be open when the phone hangs up, so exit once stdout has drained.
process.stdout.write("", () => process.exit(code));
