<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import { Camera, LoaderCircle } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import UserAvatar from "./UserAvatar.vue";

const props = defineProps<{ accountId: string; name: string; url?: string | null; teacher: boolean; disabled: boolean }>();
const emit = defineEmits<{ uploaded: [id: string, url: string]; uploading: [busy: boolean]; expired: [] }>();
const picker = ref<HTMLInputElement>();
const chooseButton = ref<HTMLButtonElement>();
const canvas = ref<HTMLCanvasElement>();
const editing = ref(false), loading = ref(false), saving = ref(false);
const error = ref(""), success = ref("");
const zoom = ref(1);
const side = 300;
let image: HTMLImageElement | null = null;
let x = 0, y = 0, revision = 0;
let drag: { id: number; x: number; y: number } | null = null;
const clamp = (value: number, bound: number) => Math.max(-bound, Math.min(bound, value));

function draw() {
  const ctx = canvas.value?.getContext("2d");
  if (!ctx || !image) return;
  const scale = side / Math.min(image.naturalWidth, image.naturalHeight) * zoom.value;
  const w = image.naturalWidth * scale, h = image.naturalHeight * scale;
  x = clamp(x, (w - side) / 2);
  y = clamp(y, (h - side) / 2);
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, side, side);
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(image, (side - w) / 2 + x, (side - h) / 2 + y, w, h);
}
watch(zoom, draw);
function pointerDown(e: PointerEvent) {
  if (!canvas.value || saving.value || !e.isPrimary || e.button !== 0) return;
  canvas.value.setPointerCapture(e.pointerId);
  drag = { id: e.pointerId, x: e.clientX, y: e.clientY };
}
function pointerMove(e: PointerEvent) {
  if (!canvas.value || !drag || drag.id !== e.pointerId) return;
  const scale = side / canvas.value.getBoundingClientRect().width;
  x += (e.clientX - drag.x) * scale;
  y += (e.clientY - drag.y) * scale;
  drag.x = e.clientX; drag.y = e.clientY;
  draw();
}
function keyboard(e: KeyboardEvent) {
  const delta: Record<string, [number, number]> = { ArrowLeft: [-5, 0], ArrowRight: [5, 0], ArrowUp: [0, -5], ArrowDown: [0, 5] };
  const move = delta[e.key];
  if (!move || saving.value) return;
  e.preventDefault(); x += move[0]; y += move[1]; draw();
}
function clearCrop() {
  revision++;
  editing.value = false; loading.value = false; image = null; drag = null;
}
async function focusChooser() {
  await nextTick();
  chooseButton.value?.focus();
}
async function selectFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  clearCrop(); error.value = ""; success.value = "";
  if (file.size > 2 * 1024 * 1024) { error.value = "头像文件不能超过 2 MB，请选择较小的图片。"; return; }
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
    error.value = "请选择 JPG、PNG 或 WebP 图片。"; return;
  }
  const version = revision;
  const objectUrl = URL.createObjectURL(file);
  loading.value = true;
  try {
    const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer());
    const tag = String.fromCharCode(...bytes);
    const valid = file.type === "image/png" ? tag.startsWith("\x89PNG\r\n\x1a\n")
      : file.type === "image/jpeg" ? tag.startsWith("\xff\xd8\xff") : tag.startsWith("RIFF") && tag.endsWith("WEBP");
    if (!valid) throw new Error();
    const decoded = new Image();
    decoded.src = objectUrl;
    await decoded.decode();
    if (version !== revision) return;
    image = decoded; zoom.value = 1; x = 0; y = 0; editing.value = true;
    await nextTick(); draw(); canvas.value?.focus();
  } catch {
    if (version === revision) error.value = "图片格式不符或无法打开，请重新选择。";
  } finally {
    URL.revokeObjectURL(objectUrl);
    if (version === revision) loading.value = false;
  }
}
async function upload() {
  if (!canvas.value || saving.value || props.disabled) return;
  const version = revision;
  saving.value = true; emit("uploading", true); error.value = "";
  try {
    const blob = await new Promise<Blob | null>(resolve => canvas.value!.toBlob(resolve, "image/jpeg", 0.88));
    if (version !== revision) return;
    if (!blob) throw new Error("图片处理失败，请重新选择。");
    const data = new FormData();
    data.append("file", blob, "avatar.jpg");
    const result = await api.request<{ avatar_url: string }>("/upload/avatar", "POST", data);
    emit("uploaded", props.accountId, result.avatar_url);
    clearCrop(); success.value = "头像已更新。";
  } catch (e) {
    if (e instanceof ApiFailure && (e.status === 401 || e.code === "SESSION_CHANGED")) emit("expired");
    else error.value = e instanceof Error ? e.message : "上传失败，请重试。";
  } finally {
    saving.value = false; emit("uploading", false);
    if (!editing.value) void focusChooser();
  }
}
onBeforeUnmount(clearCrop);
</script>

<template>
  <section aria-label="个人头像" class="space-y-4 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-8">
    <div class="flex flex-wrap items-center gap-4">
      <UserAvatar :url="url" :name="name" :color-key="accountId" class="size-16 rounded-2xl text-2xl"/>
      <div class="min-w-40 flex-1 space-y-1">
        <h2 class="text-xl font-bold">个人头像</h2>
        <p class="text-[15px] text-secondary-foreground">{{ teacher ? '上传清晰的本人正面生活照或证件照，更容易获得家长信任' : '头像选填，也可以保留展示名首字。' }}</p>
        <p class="text-sm text-muted-foreground">JPG、PNG 或 WebP，最大 2 MB</p>
      </div>
      <input ref="picker" type="file" hidden accept="image/jpeg,image/png,image/webp" aria-label="选择头像文件" @change="selectFile">
      <Button as-child variant="outline"><button ref="chooseButton" type="button" :disabled="disabled || saving" @click="picker?.click()"><Camera/>选择图片</button></Button>
    </div>
    <div v-if="loading" role="status"><Skeleton class="aspect-square w-44 rounded-xl"/><span class="sr-only">正在读取图片</span></div>
    <div v-if="editing" class="grid gap-4 rounded-xl bg-slate-50 p-4 sm:grid-cols-[minmax(0,15rem)_1fr] sm:items-center">
      <canvas ref="canvas" :width="side" :height="side" tabindex="0" aria-label="头像裁剪预览，可用方向键调整位置"
        class="aspect-square w-full max-w-[300px] touch-none cursor-grab rounded-lg active:cursor-grabbing"
        @pointerdown="pointerDown" @pointermove="pointerMove" @lostpointercapture="drag = null" @keydown="keyboard"/>
      <div class="space-y-4">
        <p class="text-sm text-secondary-foreground">拖动照片调整位置，方框内的画面将保存为头像。</p>
        <label class="grid gap-2 text-sm font-semibold">缩放 {{ Math.round(zoom * 100) }}%
          <input v-model.number="zoom" type="range" min="1" max="3" step="0.01" aria-label="缩放头像" :disabled="saving" class="w-full accent-primary"/>
        </label>
        <div class="flex flex-wrap gap-2">
          <Button type="button" :disabled="disabled || saving" @click="upload"><LoaderCircle v-if="saving" class="motion-safe:animate-spin"/>{{ saving ? '上传中…' : '保存头像' }}</Button>
          <Button type="button" variant="ghost" :disabled="saving" @click="clearCrop(); error = ''; focusChooser()">取消裁剪</Button>
        </div>
      </div>
    </div>
    <p v-if="error" role="alert" class="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-800">{{ error }}</p>
    <p v-if="success" role="status" class="text-sm font-medium text-primary">{{ success }}</p>
  </section>
</template>
