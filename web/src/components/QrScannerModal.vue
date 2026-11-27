<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { QrCode, X } from 'lucide-vue-next'

const props = defineProps<{
  title?: string
}>()

const emit = defineEmits<{
  (e: 'scanned', value: string): void
  (e: 'close'): void
}>()

const videoRef = ref<HTMLVideoElement | null>(null)
const errorText = ref<string | null>(null)
const scanning = ref(false)

let stream: MediaStream | null = null
let rafId: number | null = null
let stopped = false

interface NativeBarcodeDetector {
  detect(source: ImageBitmapSource): Promise<Array<{ rawValue?: string }>>
}

function stopCamera(): void {
  stopped = true
  if (rafId !== null) {
    window.cancelAnimationFrame(rafId)
    rafId = null
  }
  if (stream) {
    stream.getTracks().forEach((t) => t.stop())
    stream = null
  }
  if (videoRef.value) {
    videoRef.value.srcObject = null
  }
}

onMounted(async () => {
  const DetectorCtor = (window as unknown as {
    BarcodeDetector?: new (opts: { formats: string[] }) => NativeBarcodeDetector
  }).BarcodeDetector

  if (!DetectorCtor) {
    errorText.value =
      'Browser ini belum mendukung BarcodeDetector untuk memindai QR langsung lewat kamera. Gunakan Chrome/Edge terbaru atau tempel kodenya secara manual.'
    return
  }

  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    })
    if (stopped) {
      stopCamera()
      return
    }
    if (videoRef.value) {
      videoRef.value.srcObject = stream
      await videoRef.value.play()
    }
    const detector = new DetectorCtor({ formats: ['qr_code'] })
    scanning.value = true

    let lastCheck = 0
    const tick = async (now: number) => {
      if (stopped) return
      if (now - lastCheck > 180 && videoRef.value && videoRef.value.readyState >= 2) {
        lastCheck = now
        try {
          const results = await detector.detect(videoRef.value)
          const raw = results.find((r) => r.rawValue)?.rawValue
          if (raw) {
            stopCamera()
            emit('scanned', raw)
            return
          }
        } catch {
          // Lanjutkan frame berikutnya
        }
      }
      rafId = window.requestAnimationFrame(tick)
    }
    rafId = window.requestAnimationFrame(tick)
  } catch (e) {
    errorText.value = e instanceof Error ? `Gagal membuka kamera pemindai: ${e.message}` : 'Gagal membuka kamera.'
  }
})

onBeforeUnmount(() => {
  stopCamera()
})
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <div class="modal-card" style="max-width: 440px">
      <div class="modal-header">
        <div class="row-gap">
          <QrCode :size="17" color="#3D6FA5" />
          <h3 class="modal-title">{{ props.title || 'Pindai Kode QR' }}</h3>
        </div>
        <button class="btn btn-icon btn-sm" type="button" @click="emit('close')">
          <X :size="15" />
        </button>
      </div>

      <div v-if="errorText" class="alert alert-warning">{{ errorText }}</div>

      <div v-else class="scanner-box">
        <video ref="videoRef" muted playsinline autoplay class="scanner-video"></video>
        <div class="scanner-reticle"></div>
      </div>

      <p class="hint-text" style="margin-top: 10px; text-align: center">
        {{ scanning ? 'Arahkan kamera ke kode QR pada perangkat pasangan…' : 'Menyiapkan kamera pemindai…' }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.scanner-box {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  background: #141c24;
  border-radius: 12px;
  overflow: hidden;
}

.scanner-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.scanner-reticle {
  position: absolute;
  inset: 18%;
  border: 2px solid rgba(61, 111, 165, 0.9);
  border-radius: 14px;
  box-shadow: 0 0 0 9999px rgba(15, 23, 32, 0.35);
  pointer-events: none;
}
</style>
