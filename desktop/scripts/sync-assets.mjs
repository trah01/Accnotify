import { mkdir, copyFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const root = new URL("../", import.meta.url);
await mkdir(new URL("src/vendor/", root), { recursive: true });
for (const [source, destination] of [
  ["marked/lib/marked.umd.js", "marked.js"], ["marked/LICENSE.md", "marked.LICENSE.md"],
  ["dompurify/dist/purify.min.js", "purify.js"], ["dompurify/LICENSE", "dompurify.LICENSE"]
]) await copyFile(fileURLToPath(new URL(`node_modules/${source}`, root)), fileURLToPath(new URL(`src/vendor/${destination}`, root)));
