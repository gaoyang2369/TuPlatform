import { normalize } from "node:path";
import { fileURLToPath } from "node:url";

const theme = fileURLToPath(new URL("./src/styles/theme.css", import.meta.url));
const legacy = fileURLToPath(new URL("./src/account.css", import.meta.url));
const ui = ":is([data-ui], [data-slot])";

// 新页面可用 data-ui 开启 Preflight，shadcn 组件及其 Portal 自带 data-slot。
// 旧样式表迁移完成后即可移除这层隔离。
export default {
  plugins: [{
    postcssPlugin: "isolate-legacy-and-ui-styles",
    Once(root, { AtRule }) {
      const file = normalize(root.source.input.file);
      if (file === legacy) {
        // :where 保持原有优先级。原生 dialog 的 ::backdrop 需保留在选择器末尾。
        const outsideUi = `:where(:not(${ui}, ${ui} *))`;
        root.walkRules((rule) => {
          rule.selectors = rule.selectors.map((selector) => selector.replace(/(?=::|$)/, outsideUi));
        });
      } else if (file === theme) {
        root.walkAtRules("layer", (layer) => {
          if (layer.params !== "base" || !layer.nodes) return;
          const scope = new AtRule({ name: "scope", params: `(${ui})` });
          scope.append(layer.nodes.slice());
          layer.append(scope);
        });
      }
    },
  }],
};
