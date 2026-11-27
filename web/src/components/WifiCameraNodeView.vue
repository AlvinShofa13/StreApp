<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowLeft, Camera, ExternalLink, RefreshCw, ScanLine, Smartphone, Wifi } from 'lucide-vue-next'
import {
  AndroidLanWebSender,
  WifiNodeSession,
  buildAndroidWebBridgeUrl,
  parseAndroidPairingUri,
  parseRoomPairingUrl,
  type AndroidHostPairing,
  type WifiPairingStatus,
} from '../engine/wifiPairing'
import QrScannerModal from './QrScannerModal.vue'

const emit = defineEmits<{
  (e: 'exit'): void
}>()

const targetMode = ref<'WEB_HOST' | 'ANDROID_HOST'>('WEB_HOST')
const videoRef = ref<HTMLVideoElement | null>(null)
const facingMode = ref<'environment' | 'user'>('environment')
const portraitMode = ref(true)
const sendAudio = ref(false)
const linkInput = ref('')
const activeRoomId = ref('')
const status = ref<WifiPairingStatus>('IDLE')
const statusMsg = ref('')
const showScanner = ref(false)

// Target StreApp Mobile (Android Host)
const androidHostIp = ref('')
const androidWebPort = ref('45679')
const androidToken = ref('')
const isHttpsPage = typeof window !== 'undefined' && window.location.protocol === 'https:'

let localStream: MediaStream | null = null
const nodeSession = new WifiNodeSession()
nodeSession.onStatus = (st, msg) => {
  status.value = st
  if (msg) statusMsg.value = msg
}

const androidSender = new AndroidLanWebSender()
androidSender.onStatus = (st, msg) => {
  status.value = st
  if (msg) statusMsg.value = msg
}

const currentAndroidPairing = computed<AndroidHostPairing | null>(() => {
  const host = androidHostIp.value.trim()
  const token = androidToken.value.trim()
  const webPort = Number(androidWebPort.value) || 45679
  if (!host || !token) return null
  return { host, port: 45678, webPort, token, hostId: 'manual' }
})

const androidBridgeUrl = computed(() =>
  currentAndroidPairing.value ? buildAndroidWebBridgeUrl(currentAndroidPairing.value) : '',
)

async function startLocalCamera(): Promise<void> {
  const prevStream = localStream
  statusMsg.value = ''
  try {
    const videoConstraint: MediaTrackConstraints = {
      facingMode: { ideal: facingMode.value },
      width: { ideal: portraitMode.value ? 720 : 1280 },
      height: { ideal: portraitMode.value ? 1280 : 720 },
      frameRate: { ideal: 30, max: 30 },
    }
    const nextStream = await navigator.mediaDevices.getUserMedia({
      video: videoConstraint,
      audio: sendAudio.value,
    })
    localStream = nextStream
    if (videoRef.value) {
      videoRef.value.srcObject = nextStream
      await videoRef.value.play()
    }
    // Ganti track secara mulus tanpa memutus koneksi WebRTC yang sudah aktif!
    if (status.value === 'CONNECTED' || status.value === 'CONNECTING') {
      await nodeSession.replaceStream(nextStream)
    }
    if (prevStream) {
      prevStream.getTracks().forEach((t) => t.stop())
    }
  } catch (e) {
    statusMsg.value = e instanceof Error ? `Gagal membuka kamera: ${e.message}` : 'Gagal membuka kamera.'
  }
}

function stopLocalCamera(): void {
  if (localStream) {
    localStream.getTracks().forEach((t) => t.stop())
    localStream = null
  }
  if (videoRef.value) {
    videoRef.value.srcObject = null
  }
}

async function switchCamera(): Promise<void> {
  facingMode.value = facingMode.value === 'environment' ? 'user' : 'environment'
  await startLocalCamera()
}

async function setOrientation(portrait: boolean): Promise<void> {
  if (portraitMode.value === portrait) return
  portraitMode.value = portrait
  await startLocalCamera()
}

async function connectToWebHost(rawUrlOrCode?: string): Promise<void> {
  const source = (rawUrlOrCode ?? linkInput.value).trim()
  const room = parseRoomPairingUrl(source)
  if (!room) {
    status.value = 'ERROR'
    statusMsg.value = 'QR atau tautan sesi tidak dikenali. Pindai ulang QR di layar StreApp Web.'
    return
  }
  if (!localStream) {
    await startLocalCamera()
  }
  if (!localStream) return
  activeRoomId.value = room.roomId
  statusMsg.value = `Menyambungkan otomatis ke sesi ${room.roomId}…`
  try {
    await nodeSession.connectToRoom(room, localStream, 'Kamera HP (Wi-Fi)')
  } catch (e) {
    status.value = 'ERROR'
    statusMsg.value = e instanceof Error ? e.message : 'Gagal menyambungkan ke Host Web.'
  }
}

function connectToAndroidHost(): void {
  const info = currentAndroidPairing.value
  if (!info) {
    statusMsg.value = 'Pindai QR StreApp Android atau masukkan IP Host dan Token terlebih dahulu.'
    return
  }
  if (!videoRef.value) return
  statusMsg.value = ''
  androidSender.start(info, videoRef.value, 20)
}

function openAndroidLanBridge(): void {
  if (!androidBridgeUrl.value) return
  window.open(androidBridgeUrl.value, '_blank', 'noopener')
}

async function onQrScanned(raw: string): Promise<void> {
  showScanner.value = false
  const androidParsed = parseAndroidPairingUri(raw)
  if (androidParsed) {
    targetMode.value = 'ANDROID_HOST'
    androidHostIp.value = androidParsed.host
    androidWebPort.value = String(androidParsed.webPort)
    androidToken.value = androidParsed.token
    statusMsg.value = `QR StreApp Android terdeteksi (${androidParsed.host}:${androidParsed.webPort}).`
    if (!isHttpsPage) {
      connectToAndroidHost()
    }
    return
  }
  targetMode.value = 'WEB_HOST'
  linkInput.value = raw.trim()
  await connectToWebHost(raw)
}

function disconnect(): void {
  nodeSession.close()
  androidSender.stop()
  activeRoomId.value = ''
  statusMsg.value = ''
}

onMounted(async () => {
  await startLocalCamera()
  // Jika dibuka langsung dari hasil scan QR StreApp Web (?wifiNode=1#r=...&k=...), OTOMATIS TERSAMBUNG!
  const parsed = parseRoomPairingUrl(window.location.href)
  if (parsed) {
    targetMode.value = 'WEB_HOST'
    linkInput.value = window.location.href
    await connectToWebHost(window.location.href)
  }
})

onBeforeUnmount(() => {
  nodeSession.close()
  androidSender.stop()
  stopLocalCamera()
})
</script>

<template>
  <div class="node-shell">
    <header class="topbar">
      <div class="row-gap">
        <button class="btn btn-sm" type="button" @click="emit('exit')">
          <ArrowLeft :size="15" />
          <span>Kembali ke Studio</span>
        </button>
        <div class="topbar-title" style="font-size: 15px">Mode Kamera Pengirim (Wi-Fi)</div>
      </div>
      <span
        class="badge"
        :class="status === 'CONNECTED' ? 'badge-success' : status === 'ERROR' ? 'badge-record' : 'badge-accent'"
      >
        <Wifi :size="12" />
        <span>
          {{
            status === 'CONNECTED'
              ? 'Tersambung'
              : status === 'CONNECTING' || status === 'PREPARING'
                ? 'Menyambungkan…'
                : 'Siaga'
          }}
        </span>
      </span>
    </header>

    <main class="node-body">
      <div
        v-if="statusMsg"
        class="alert"
        :class="status === 'CONNECTED' ? 'alert-success' : status === 'ERROR' ? 'alert-error' : 'alert-info'"
      >
        {{ statusMsg }}
      </div>

      <div class="card">
        <div class="preview-frame" :class="{ portrait: portraitMode }">
          <video ref="videoRef" muted playsinline autoplay class="node-video"></video>
        </div>

        <div class="chip-group" style="margin-top: 12px">
          <button class="btn btn-sm" type="button" @click="switchCamera">
            <RefreshCw :size="14" />
            <span>{{ facingMode === 'environment' ? 'Kamera Belakang' : 'Kamera Depan' }}</span>
          </button>
          <button class="chip" :class="{ active: portraitMode }" type="button" @click="setOrientation(true)">
            Potret (9:16)
          </button>
          <button class="chip" :class="{ active: !portraitMode }" type="button" @click="setOrientation(false)">
            Lanskap (16:9)
          </button>
          <label class="row-gap" style="font-size: 12px; color: var(--text-secondary); margin-left: auto">
            <input v-model="sendAudio" type="checkbox" @change="startLocalCamera()" />
            <span>Kirim Audio Mic</span>
          </label>
        </div>
      </div>

      <div class="card col-gap">
        <div class="chip-group">
          <button
            class="chip"
            :class="{ active: targetMode === 'WEB_HOST' }"
            type="button"
            @click="targetMode = 'WEB_HOST'"
          >
            <Wifi :size="13" />
            <span>Sambung ke StreApp Web</span>
          </button>
          <button
            class="chip"
            :class="{ active: targetMode === 'ANDROID_HOST' }"
            type="button"
            @click="targetMode = 'ANDROID_HOST'"
          >
            <Smartphone :size="13" />
            <span>Sambung ke StreApp Mobile (Android)</span>
          </button>
        </div>

        <button class="btn btn-accent-outline" type="button" @click="showScanner = true">
          <ScanLine :size="16" />
          <span>Pindai QR Host (Web atau Android)</span>
        </button>

        <!-- Mode A: Sambung Otomatis ke StreApp Web (1x Scan QR) -->
        <template v-if="targetMode === 'WEB_HOST'">
          <div class="row-gap">
            <Camera :size="16" color="#3D6FA5" />
            <h3 class="card-title">
              {{ activeRoomId ? `Sesi Aktif: ${activeRoomId}` : 'Kirim Kamera ke StreApp Web' }}
            </h3>
          </div>
          <p class="hint-text">
            Pindai QR di layar StreApp Web (atau tempel tautan sesi di bawah) — kamera otomatis tersambung langsung tanpa kode balasan.
          </p>

          <input
            v-model="linkInput"
            class="input"
            placeholder="Atau tempel tautan sesi dari StreApp Web di sini…"
          />

          <div class="row-gap">
            <button class="btn btn-primary" style="flex: 1" type="button" @click="connectToWebHost()">
              Sambungkan Sekarang
            </button>
            <button v-if="status !== 'IDLE'" class="btn btn-sm" type="button" @click="disconnect">
              Putus
            </button>
          </div>
        </template>

        <!-- Mode B: Sambung ke StreApp Mobile (Android Host) -->
        <template v-else>
          <div class="row-gap">
            <Smartphone :size="16" color="#4F8A6B" />
            <h3 class="card-title">Kirim ke StreApp Mobile (Android Host)</h3>
          </div>
          <p class="hint-text">
            Di HP Android buka Pengaturan -> <strong>Tampilkan QR (Host)</strong>, lalu pindai QR-nya dengan tombol di atas atau masukkan IP dan Token-nya di bawah.
          </p>

          <div class="row-gap">
            <input v-model="androidHostIp" class="input" style="flex: 1.5" placeholder="IP HP Android (mis. 192.168.1.24)" />
            <input v-model="androidWebPort" class="input" style="flex: 0.7" placeholder="45679" />
          </div>
          <input v-model="androidToken" class="input" placeholder="Token Pairing dari HP Android" />

          <div class="row-gap">
            <button
              v-if="!isHttpsPage"
              class="btn btn-primary"
              style="flex: 1"
              type="button"
              @click="connectToAndroidHost"
            >
              Sambung Langsung ke Android (Wi-Fi)
            </button>
            <button
              v-if="androidBridgeUrl"
              class="btn btn-accent-outline"
              style="flex: 1"
              type="button"
              @click="openAndroidLanBridge"
            >
              <ExternalLink :size="15" />
              <span>Buka Jembatan LAN HP (HTTP)</span>
            </button>
            <button v-if="status !== 'IDLE'" class="btn btn-sm" type="button" @click="disconnect">
              Putus
            </button>
          </div>
          <p v-if="isHttpsPage" class="hint-text">
            Karena situs ini berjalan di HTTPS, browser memblokir koneksi langsung <code>ws://</code> ke IP lokal. Klik <strong>Buka Jembatan LAN HP (HTTP)</strong> di atas untuk langsung mengirim kamera/layar browser ke aplikasi StreApp Android di Wi-Fi yang sama.
          </p>
        </template>
      </div>
    </main>

    <QrScannerModal
      v-if="showScanner"
      title="Pindai QR Host (StreApp Web / Android)"
      @scanned="onQrScanned"
      @close="showScanner = false"
    />
  </div>
</template>

<style scoped>
.node-shell {
  min-height: 100vh;
  background: var(--bg);
}

.node-body {
  max-width: 680px;
  margin: 0 auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-frame {
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #141c24;
  border-radius: 12px;
  overflow: hidden;
  margin: 0 auto;
}

.preview-frame.portrait {
  max-width: 260px;
  aspect-ratio: 9 / 16;
}

.node-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
