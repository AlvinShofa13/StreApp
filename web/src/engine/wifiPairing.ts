/**
 * Pairing Kamera Nirkabel via Wi-Fi (1x Pindai QR Langsung Tersambung — Tanpa Answer Manual):
 * - QR super ringkas (`37x37`): `https://<domain>/?wifiNode=1#r=<roomId>&k=<kunciAES>`
 * - Offer, Answer, dan Trickle ICE ditukar otomatis secara terenkripsi AES-GCM (< 1 detik),
 *   lalu WebRTC berjalan P2P langsung di Wi-Fi lokal dengan full `rtcp-fb` (NACK/PLI/FIR),
 *   pembatasan bitrate stabil (anti-lag), dan auto-reconnect.
 * - Juga tetap mendukung arah balik: StreApp Web -> StreApp Android Host (Jembatan LAN port 45679).
 */

import { EncryptedSignalChannel, generateRoomCredentials, type SignalEnvelope } from './signalingRelay'

export type WifiPairingStatus = 'IDLE' | 'PREPARING' | 'WAITING_NODE' | 'CONNECTING' | 'CONNECTED' | 'ERROR'

const RTC_CONFIG: RTCConfiguration = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ],
  iceCandidatePoolSize: 2,
}

export interface RoomPairingInfo {
  roomId: string
  keyB64: string
}

export interface AndroidHostPairing {
  host: string
  port: number
  webPort: number
  token: string
  hostId: string
}

/** Parsing URL/QR sesi 1-scan (`https://.../?wifiNode=1#r=<room>&k=<key>` atau `r=<room>&k=<key>`). */
export function parseRoomPairingUrl(raw: string): RoomPairingInfo | null {
  const text = raw.trim()
  const hashPart = text.includes('#') ? text.split('#')[1] : text.includes('?') ? text.split('?')[1] : text
  try {
    const params = new URLSearchParams(hashPart)
    const roomId = params.get('r')?.trim()
    const keyB64 = params.get('k')?.trim()
    if (!roomId || !keyB64) return null
    return { roomId, keyB64 }
  } catch {
    return null
  }
}

/** Mem-parse QR Code pairing dari StreApp Android Host (`streapp://pair?host=...&port=...&token=...&wport=45679`). */
export function parseAndroidPairingUri(raw: string): AndroidHostPairing | null {
  const text = raw.trim()
  if (!text.startsWith('streapp://pair?')) return null
  try {
    const query = text.slice('streapp://pair?'.length)
    const params = new URLSearchParams(query)
    const host = params.get('host')?.trim()
    const port = Number(params.get('port') || '45678')
    const webPort = Number(params.get('wport') || '45679')
    const token = params.get('token')?.trim()
    const hostId = params.get('id')?.trim() || ''
    if (!host || !token || !Number.isFinite(port)) return null
    return { host, port, webPort: Number.isFinite(webPort) ? webPort : 45679, token, hostId }
  } catch {
    return null
  }
}

export function buildAndroidWebBridgeUrl(info: AndroidHostPairing): string {
  return `http://${info.host}:${info.webPort}/?token=${encodeURIComponent(info.token)}`
}

/** Mengatur batas bitrate & framerate pengirim WebRTC agar mulus, rendah latensi, dan anti-lag di Wi-Fi. */
async function tuneVideoSender(pc: RTCPeerConnection, maxBitrateBps = 1_600_000): Promise<void> {
  for (const sender of pc.getSenders()) {
    if (sender.track?.kind === 'video') {
      try {
        if ('contentHint' in sender.track) {
          sender.track.contentHint = 'motion'
        }
        const params = sender.getParameters()
        if (!params.encodings || params.encodings.length === 0) {
          params.encodings = [{}]
        }
        params.encodings[0].maxBitrate = maxBitrateBps
        params.encodings[0].maxFramerate = 30
        ;(params as RTCRtpSendParameters & { degradationPreference?: string }).degradationPreference =
          'maintain-framerate'
        await sender.setParameters(params)
      } catch {
        // Beberapa browser mobile membatasi sebagian parameter; abaikan secara aman
      }
    }
  }
}

/** Prioritaskan codec H.264 hardware agar encode di HP jauh lebih ringan dibanding VP8/VP9 software. */
function preferHardwareH264(transceiver: RTCRtpTransceiver): void {
  try {
    if (typeof RTCRtpReceiver === 'undefined' || !RTCRtpReceiver.getCapabilities) return
    const caps = RTCRtpReceiver.getCapabilities('video')
    if (!caps || !caps.codecs || typeof transceiver.setCodecPreferences !== 'function') return
    const h264 = caps.codecs.filter((c) => c.mimeType.toLowerCase() === 'video/h264')
    const others = caps.codecs.filter((c) => c.mimeType.toLowerCase() !== 'video/h264')
    if (h264.length > 0) {
      transceiver.setCodecPreferences([...h264, ...others])
    }
  } catch {
    // Abaikan jika browser tidak mengizinkan setCodecPreferences
  }
}

/**
 * Sesi Host di StreApp Web:
 * Klik "Tampilkan QR" -> tampilkan QR kecil -> HP cukup pindai 1 kali -> otomatis tersambung!
 */
export class WifiHostSession {
  private pc: RTCPeerConnection | null = null
  private signal: EncryptedSignalChannel | null = null
  private remoteStream: MediaStream | null = null
  private pendingIce: RTCIceCandidateInit[] = []
  private remoteDescSet = false
  private negotiating = false
  private lastOfferMs = 0
  private notifiedStream: MediaStream | null = null

  onStatus?: (status: WifiPairingStatus, message?: string) => void
  onStream?: (stream: MediaStream, label: string) => void

  async startRoom(): Promise<{ roomId: string; shareUrl: string }> {
    this.close()
    this.onStatus?.('PREPARING')

    const { roomId, keyB64 } = generateRoomCredentials()
    const base = `${window.location.origin}${window.location.pathname}`
    const shareUrl = `${base}?wifiNode=1#r=${roomId}&k=${keyB64}`

    const channel = new EncryptedSignalChannel(roomId, keyB64, 'host')
    this.signal = channel
    channel.onMessage = (msg) => {
      void this.handleSignal(msg)
    }

    await channel.connect()
    this.onStatus?.('WAITING_NODE')
    return { roomId, shareUrl }
  }

  private async handleSignal(msg: SignalEnvelope): Promise<void> {
    if (msg.type === 'ready') {
      // Cegah reset berulang dari beacon ready yang datang saat koneksi sudah berjalan/tersambung
      const st = this.pc?.connectionState
      if (st === 'connected') return
      if ((this.negotiating || st === 'connecting') && Date.now() - this.lastOfferMs < 5000) return
      await this.createPeerAndSendOffer(msg.name || 'Kamera HP (Wi-Fi)')
    } else if (msg.type === 'answer' && msg.sdp && this.pc) {
      if (this.remoteDescSet) return
      try {
        await this.pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp })
        this.remoteDescSet = true
        this.negotiating = false
        for (const c of this.pendingIce) {
          await this.pc.addIceCandidate(c).catch(() => {})
        }
        this.pendingIce = []
      } catch (e) {
        this.negotiating = false
        this.onStatus?.('ERROR', e instanceof Error ? e.message : 'Gagal menerapkan Answer WebRTC.')
      }
    } else if (msg.type === 'ice' && msg.candidate) {
      if (this.pc && this.remoteDescSet) {
        await this.pc.addIceCandidate(msg.candidate).catch(() => {})
      } else {
        this.pendingIce.push(msg.candidate)
      }
    } else if (msg.type === 'bye') {
      this.closePeerOnly()
      this.onStatus?.('WAITING_NODE', 'Kamera HP terputus. Menunggu sambung ulang…')
    }
  }

  private async createPeerAndSendOffer(nodeLabel: string): Promise<void> {
    this.closePeerOnly()
    this.negotiating = true
    this.lastOfferMs = Date.now()
    this.onStatus?.('CONNECTING')

    const pc = new RTCPeerConnection(RTC_CONFIG)
    this.pc = pc
    this.remoteDescSet = false
    this.pendingIce = []
    this.notifiedStream = null

    const videoTransceiver = pc.addTransceiver('video', { direction: 'recvonly' })
    preferHardwareH264(videoTransceiver)
    pc.addTransceiver('audio', { direction: 'recvonly' })

    const incoming = new MediaStream()
    this.remoteStream = incoming

    pc.ontrack = (ev) => {
      // Kurangi buffer jitter WebRTC agar video real-time tanpa delay/lag menumpuk
      try {
        const recv = ev.receiver as RTCRtpReceiver & {
          playoutDelayHint?: number
          jitterBufferTarget?: number
        }
        if ('playoutDelayHint' in recv) recv.playoutDelayHint = 0
        if ('jitterBufferTarget' in recv) recv.jitterBufferTarget = 0
      } catch {}

      const targetStream = ev.streams[0] ?? incoming
      if (!ev.streams[0]) {
        incoming.addTrack(ev.track)
      }
      this.remoteStream = targetStream
      if (this.notifiedStream !== targetStream || ev.track.kind === 'audio') {
        this.notifiedStream = targetStream
        this.onStream?.(targetStream, nodeLabel)
      }
      this.onStatus?.('CONNECTED')
    }

    pc.onicecandidate = (ev) => {
      if (ev.candidate) {
        void this.signal?.send({ type: 'ice', candidate: ev.candidate.toJSON() })
      }
    }

    pc.onconnectionstatechange = () => {
      const st = pc.connectionState
      if (st === 'connected') {
        this.negotiating = false
        this.onStatus?.('CONNECTED')
      } else if (st === 'disconnected') {
        this.onStatus?.('CONNECTING', 'Jaringan Wi-Fi sempat goyang, mencoba memulihkan…')
      } else if (st === 'failed') {
        this.negotiating = false
        this.onStatus?.('WAITING_NODE', 'Koneksi terputus — menunggu kamera HP menyambung ulang otomatis…')
      }
    }

    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    if (pc.localDescription?.sdp) {
      await this.signal?.send({ type: 'offer', sdp: pc.localDescription.sdp })
    }
  }

  private closePeerOnly(): void {
    this.pendingIce = []
    this.remoteDescSet = false
    this.negotiating = false
    this.notifiedStream = null
    if (this.pc) {
      try { this.pc.close() } catch {}
      this.pc = null
    }
  }

  close(): void {
    void this.signal?.send({ type: 'bye' })
    this.signal?.close()
    this.signal = null
    this.closePeerOnly()
    if (this.remoteStream) {
      this.remoteStream.getTracks().forEach((t) => t.stop())
      this.remoteStream = null
    }
    this.onStatus?.('IDLE')
  }
}

/**
 * Sesi Pengirim (HP B / Browser Mobile) — 1x Scan QR langsung terhubung otomatis + auto-reconnect!
 */
export class WifiNodeSession {
  private pc: RTCPeerConnection | null = null
  private signal: EncryptedSignalChannel | null = null
  private localStream: MediaStream | null = null
  private pendingIce: RTCIceCandidateInit[] = []
  private remoteDescSet = false
  private readyTimer: number | null = null
  private offerReceived = false
  private deviceLabel = 'Kamera HP (Wi-Fi)'
  private targetBitrateBps = 1_600_000

  onStatus?: (status: WifiPairingStatus, message?: string) => void

  async connectToRoom(
    info: RoomPairingInfo,
    stream: MediaStream,
    label?: string,
    maxBitrateBps = 1_600_000,
  ): Promise<void> {
    this.close()
    this.localStream = stream
    this.deviceLabel = label || 'Kamera HP (Wi-Fi)'
    this.targetBitrateBps = maxBitrateBps
    this.offerReceived = false
    this.onStatus?.('CONNECTING', 'Menyambungkan otomatis ke StreApp Web Host…')

    const channel = new EncryptedSignalChannel(info.roomId, info.keyB64, 'node')
    this.signal = channel
    channel.onMessage = (msg) => {
      void this.handleSignal(msg)
    }

    await channel.connect()
    this.startReadyBeacon()
  }

  /** Ganti track kamera/orientasi secara mulus tanpa memutus koneksi WebRTC! */
  async replaceStream(newStream: MediaStream, maxBitrateBps?: number): Promise<void> {
    this.localStream = newStream
    if (maxBitrateBps) this.targetBitrateBps = maxBitrateBps
    if (!this.pc) return
    const newVideo = newStream.getVideoTracks()[0]
    const newAudio = newStream.getAudioTracks()[0]
    for (const sender of this.pc.getSenders()) {
      if (sender.track?.kind === 'video' && newVideo) {
        await sender.replaceTrack(newVideo).catch(() => {})
      } else if (sender.track?.kind === 'audio' && newAudio) {
        await sender.replaceTrack(newAudio).catch(() => {})
      }
    }
    await tuneVideoSender(this.pc, this.targetBitrateBps)
  }

  private startReadyBeacon(): void {
    this.stopReadyBeacon()
    const sendReady = () => {
      if (!this.offerReceived) {
        void this.signal?.send({ type: 'ready', name: this.deviceLabel })
      }
    }
    sendReady()
    this.readyTimer = window.setInterval(sendReady, 2200)
  }

  private stopReadyBeacon(): void {
    if (this.readyTimer !== null) {
      window.clearInterval(this.readyTimer)
      this.readyTimer = null
    }
  }

  private async handleSignal(msg: SignalEnvelope): Promise<void> {
    if (msg.type === 'offer' && msg.sdp && this.localStream) {
      if (this.pc?.connectionState === 'connected') return
      this.offerReceived = true
      this.stopReadyBeacon()
      await this.acceptOfferAndSendAnswer(msg.sdp)
    } else if (msg.type === 'ice' && msg.candidate) {
      if (this.pc && this.remoteDescSet) {
        await this.pc.addIceCandidate(msg.candidate).catch(() => {})
      } else {
        this.pendingIce.push(msg.candidate)
      }
    } else if (msg.type === 'bye') {
      this.closePeerOnly()
      this.onStatus?.('IDLE', 'Sesi ditutup oleh Host.')
    }
  }

  private async acceptOfferAndSendAnswer(offerSdp: string): Promise<void> {
    this.closePeerOnly()
    const stream = this.localStream
    if (!stream) return

    const pc = new RTCPeerConnection(RTC_CONFIG)
    this.pc = pc
    this.remoteDescSet = false
    this.pendingIce = []

    stream.getTracks().forEach((track) => {
      pc.addTrack(track, stream)
    })

    pc.onicecandidate = (ev) => {
      if (ev.candidate) {
        void this.signal?.send({ type: 'ice', candidate: ev.candidate.toJSON() })
      }
    }

    pc.onconnectionstatechange = () => {
      const st = pc.connectionState
      if (st === 'connected') {
        this.onStatus?.('CONNECTED', 'Tersambung langsung (P2P Wi-Fi) ke StreApp Web!')
        void tuneVideoSender(pc, this.targetBitrateBps)
      } else if (st === 'disconnected') {
        this.onStatus?.('CONNECTING', 'Jaringan goyang — menyambung ulang otomatis…')
      } else if (st === 'failed') {
        this.offerReceived = false
        this.onStatus?.('CONNECTING', 'Menyambung ulang otomatis ke Host…')
        this.startReadyBeacon()
      }
    }

    await pc.setRemoteDescription({ type: 'offer', sdp: offerSdp })
    this.remoteDescSet = true
    for (const c of this.pendingIce) {
      await pc.addIceCandidate(c).catch(() => {})
    }
    this.pendingIce = []

    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    await tuneVideoSender(pc, this.targetBitrateBps)

    if (pc.localDescription?.sdp) {
      await this.signal?.send({ type: 'answer', sdp: pc.localDescription.sdp })
    }
  }

  private closePeerOnly(): void {
    this.pendingIce = []
    this.remoteDescSet = false
    if (this.pc) {
      try { this.pc.close() } catch {}
      this.pc = null
    }
  }

  close(): void {
    this.stopReadyBeacon()
    void this.signal?.send({ type: 'bye' })
    this.signal?.close()
    this.signal = null
    this.closePeerOnly()
    this.offerReceived = false
    this.onStatus?.('IDLE')
  }
}

/**
 * Pengirim langsung dari Browser Web ke StreApp Android Host lewat WebSocket LAN
 * (`ws://<ip>:45679/ws?token=<token>`).
 */
export class AndroidLanWebSender {
  private ws: WebSocket | null = null
  private timer: number | null = null
  private canvas: HTMLCanvasElement
  private ctx: CanvasRenderingContext2D | null
  private busy = false

  onStatus?: (status: WifiPairingStatus, message?: string) => void

  constructor() {
    this.canvas = document.createElement('canvas')
    this.ctx = this.canvas.getContext('2d')
  }

  start(info: AndroidHostPairing, videoEl: HTMLVideoElement, fps = 20): void {
    this.stop()
    if (window.location.protocol === 'https:') {
      this.onStatus?.(
        'ERROR',
        'Browser memblokir WebSocket LAN (ws://) dari halaman HTTPS. Klik "Buka Jembatan LAN HP" di bawah untuk mengirim langsung ke StreApp Android.'
      )
      return
    }

    const wsUrl = `ws://${info.host}:${info.webPort}/ws?token=${encodeURIComponent(info.token)}`
    this.onStatus?.('CONNECTING')
    const ws = new WebSocket(wsUrl)
    ws.binaryType = 'arraybuffer'
    this.ws = ws

    ws.onopen = () => {
      this.onStatus?.('CONNECTED', `Tersambung ke StreApp Android (${info.host}:${info.webPort})`)
      const intervalMs = Math.max(33, Math.round(1000 / fps))
      this.timer = window.setInterval(() => {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN || this.busy) return
        if (videoEl.readyState < 2 || videoEl.videoWidth <= 0 || videoEl.videoHeight <= 0) return
        if (this.ws.bufferedAmount > 512 * 1024) return

        const maxDim = 960
        const scale = Math.min(1, maxDim / Math.max(videoEl.videoWidth, videoEl.videoHeight))
        const w = Math.max(16, Math.round(videoEl.videoWidth * scale))
        const h = Math.max(16, Math.round(videoEl.videoHeight * scale))
        if (this.canvas.width !== w || this.canvas.height !== h) {
          this.canvas.width = w
          this.canvas.height = h
        }
        this.ctx?.drawImage(videoEl, 0, 0, w, h)
        this.busy = true
        this.canvas.toBlob(
          (blob) => {
            if (blob && this.ws && this.ws.readyState === WebSocket.OPEN) {
              blob.arrayBuffer().then((buf) => {
                if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                  this.ws.send(buf)
                }
                this.busy = false
              }).catch(() => { this.busy = false })
            } else {
              this.busy = false
            }
          },
          'image/jpeg',
          0.74
        )
      }, intervalMs)
    }

    ws.onerror = () => {
      this.onStatus?.('ERROR', 'Gagal menyambung ke StreApp Android. Pastikan Wi-Fi sama dan Host QR sedang aktif.')
    }

    ws.onclose = () => {
      if (this.timer !== null) {
        window.clearInterval(this.timer)
        this.timer = null
      }
      if (this.ws === ws) {
        this.ws = null
        this.onStatus?.('IDLE')
      }
    }
  }

  stop(): void {
    if (this.timer !== null) {
      window.clearInterval(this.timer)
      this.timer = null
    }
    if (this.ws) {
      const s = this.ws
      this.ws = null
      try { s.close() } catch {}
    }
    this.busy = false
    this.onStatus?.('IDLE')
  }
}
