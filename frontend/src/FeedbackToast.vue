<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";

const props = defineProps<{ message: string; kind: "error" | "success"; fieldErrors?: Record<string, string> }>();
const visible = ref(true);
let timer: ReturnType<typeof setTimeout> | undefined;
function pause() { clearTimeout(timer); }
function resume() { pause(); timer = setTimeout(() => { visible.value = false; }, 5000); }
watch(() => props.message, () => { visible.value = true; resume(); }, { immediate: true });
onBeforeUnmount(pause);
</script>

<template>
  <Transition name="toast">
    <div v-if="visible" class="feedback-toast" @mouseenter="pause" @mouseleave="resume" @focusin="pause" @focusout="resume">
      <div :class="['message', kind]" :role="kind === 'error' ? 'alert' : 'status'" aria-atomic="true">
        <div class="toast-content">{{ message }}<ul v-if="fieldErrors && Object.keys(fieldErrors).length"><li v-for="(value, field) in fieldErrors" :key="field"><a :href="'#field-' + field">{{ value }}</a></li></ul></div>
        <button type="button" class="toast-close" aria-label="关闭提示" @click="visible = false">×</button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.feedback-toast{position:fixed;z-index:1000;top:24px;left:50%;transform:translateX(-50%);width:max-content;max-width:min(560px,calc(100vw - 32px))}
.feedback-toast .message{display:flex;align-items:flex-start;gap:16px;margin:0;padding:14px 16px;box-shadow:0 8px 30px #20352226;max-height:calc(100dvh - 48px);overflow:auto}
.toast-content{min-width:0;flex:1}.toast-close{flex:none;background:transparent;color:inherit;font-size:22px;line-height:1;width:28px;height:28px;border-radius:4px;padding:0}
.toast-enter-active,.toast-leave-active{transition:opacity .18s ease,translate .18s ease}.toast-enter-from,.toast-leave-to{opacity:0;translate:0 -8px}
@media(prefers-reduced-motion:reduce){.toast-enter-active,.toast-leave-active{transition:none}}
</style>
