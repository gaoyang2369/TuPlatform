import { useMediaQuery } from "@vueuse/core";

// Keep the interactive layout in sync with Tailwind's md breakpoint.
export function useMobileViewport() {
  return useMediaQuery("(width < 48rem)");
}
