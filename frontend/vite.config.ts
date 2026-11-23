import { fileURLToPath } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "API_");
  return {
    plugins: [vue(), tailwindcss()],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    server: { proxy: { "/api": { target: env.API_PROXY_TARGET || "http://127.0.0.1:8080", changeOrigin: false } } },
    preview: { proxy: { "/api": { target: env.API_PROXY_TARGET || "http://127.0.0.1:8080", changeOrigin: false } } },
  };
});
