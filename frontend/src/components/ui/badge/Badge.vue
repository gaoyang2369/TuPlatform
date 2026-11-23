<script setup lang="ts">
import type { PrimitiveProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import type { BadgeVariants } from "."
import { reactiveOmit } from "@vueuse/core"
import { cn } from "@/lib/utils"
import { Primitive } from "reka-ui"
import { badgeVariants } from "."

const props = withDefaults(defineProps<PrimitiveProps & {
  variant?: BadgeVariants["variant"]
  class?: HTMLAttributes["class"]
}>(), { as: "span" })

const delegatedProps = reactiveOmit(props, "class", "variant")
</script>

<template>
  <Primitive
    data-slot="badge"
    :data-variant="variant"
    :class="cn(badgeVariants({ variant }), props.class)"
    v-bind="delegatedProps"
  >
    <slot />
  </Primitive>
</template>
