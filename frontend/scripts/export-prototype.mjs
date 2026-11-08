import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

// P0 convenience artifact: one HTML file, with no remote script/font dependencies.
const dist = fileURLToPath(new URL("../dist/", import.meta.url));
let html = await readFile(path.join(dist, "index.html"), "utf8");
const scriptTag = /<script type="module" crossorigin src="([^"]+)"><\/script>/;
const styleTag = /<link rel="stylesheet" crossorigin href="([^"]+)">/;
const script = html.match(scriptTag);
const style = html.match(styleTag);
if (!script || !style)
  throw new Error(
    "Expected exactly one bundled entry and stylesheet; review exporter after build changes.",
  );
const bundledJs = await readFile(
  path.join(dist, script[1].replace(/^\//, "")),
  "utf8",
);
const bundledCss = await readFile(
  path.join(dist, style[1].replace(/^\//, "")),
  "utf8",
);
html = html.replace(
  scriptTag,
  () =>
    `<script type="module">${bundledJs.replace(/<\/script/gi, "<\\/script")}</script>`,
);
html = html.replace(
  styleTag,
  () => `<style>${bundledCss.replace(/<\/style/gi, "<\\/style")}</style>`,
);
await writeFile(path.join(dist, "p0-prototype.html"), html);
console.log("Standalone prototype: dist/p0-prototype.html");
