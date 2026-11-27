/**
 * Pairing Kamera Nirkabel via Wi-Fi (WebRTC P2P antar Web & Android + Jembatan LAN ke StreApp Mobile).
 * - Kompresi deflate-raw pada SDP agar ukuran QR Code ringkas dan cepat dipindai kamera ponsel.
 * - Mendukung pairing 2 arah:
 *   1) StreApp Mobile / Browser HP -> StreApp Web (Host WebRTC + QR Code)
 *   2) StreApp Web (Pengirim) -> StreApp Mobile Android Host (via QR streapp://pair + WebSocket LAN)
 */

export type WifiPairingStatus = 'IDLE' | 'PREPARING' | 'WAITING_ANSWER' | 'CONNECTING' | 'CONNECTED' | 'ERROR'

const RTC_CONFIG: RTCConfiguration = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ],
}

interface SignalPayload {
  t: 'offer' | 'answer'
  s: string
  n?: string
}

export interface AndroidHostPairing {
  host: string
  port: number
  webPort: number
  token: string
  hostId: string
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function base64UrlToBytes(token: string): Uint8Array {
  const padded = token.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((token.length + 3) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

async function compressDeflateRaw(bytes: Uint8Array): Promise<Uint8Array | null> {
  if (typeof CompressionStream === 'undefined') return null
  try {
    const copy = new Uint8Array(bytes)
    const stream = new Blob([copy]).stream().pipeThrough(new CompressionStream('deflate-raw'))
    const buf = await new Response(stream).arrayBuffer()
    return new Uint8Array(buf)
  } catch {
    return null
  }
}

async function decompressDeflateRaw(bytes: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('Browser tidak mendukung DecompressionStream untuk kode terkompresi.')
  }
  const copy = new Uint8Array(bytes)
  const stream = new Blob([copy]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
  const buf = await new Response(stream).arrayBuffer()
  return new Uint8Array(buf)
}

/**
 * Memangkas SDP WebRTC agar ukuran QR Code turun ~75% (dari ~157x157 menjadi ~69x69)
 * sambil mempertahankan seluruh parameter wajib DTLS/ICE, codec VP8/H.264/Opus, dan track msid/ssrc.
 */
export function minifySdp(sdp: string): string {
  const lines = sdp.replace(/\r\n/g, '\n').split('\n').filter(Boolean)
  const out: string[] = []
  let section: 'session' | 'video' | 'audio' | 'other' = 'session'
  let sectionLines: string[] = []

  const flushSection = () => {
    if (section === 'session') {
      for (const l of sectionLines) {
        if (
          l.startsWith('v=') ||
          l.startsWith('o=') ||
          l.startsWith('s=') ||
          l.startsWith('t=') ||
          l.startsWith('a=group:') ||
          l.startsWith('a=msid-semantic:')
        ) {
          out.push(l)
        }
      }
      return
    }
    if (section !== 'video' && section !== 'audio') return

    const keepPts = new Set<string>()
    let keptH264 = false
    let keptVp8 = false
    for (const l of sectionLines) {
      const m = /^a=rtpmap:(\d+)\s+([^/\s]+)/i.exec(l)
      if (!m) continue
      const pt = m[1]
      const codec = m[2].toUpperCase()
      if (section === 'video') {
        if (codec === 'VP8' && !keptVp8) {
          keepPts.add(pt)
          keptVp8 = true
        } else if (codec === 'H264' && !keptH264) {
          keepPts.add(pt)
          keptH264 = true
        }
      } else if (section === 'audio' && codec === 'OPUS' && keepPts.size < 1) {
        keepPts.add(pt)
      }
    }

    let keptCandidates = 0
    for (const l of sectionLines) {
      if (l.startsWith('m=')) {
        const parts = l.split(' ')
        const header = parts.slice(0, 3)
        const filtered = parts.slice(3).filter((pt) => keepPts.has(pt))
        out.push([...header, ...(filtered.length > 0 ? filtered : parts.slice(3, 4))].join(' '))
      } else if (
        l.startsWith('c=') ||
        l.startsWith('a=ice-ufrag:') ||
        l.startsWith('a=ice-pwd:') ||
        l.startsWith('a=ice-options:') ||
        l.startsWith('a=fingerprint:') ||
        l.startsWith('a=setup:') ||
        l.startsWith('a=mid:') ||
        l.startsWith('a=msid:') ||
        l === 'a=recvonly' ||
        l === 'a=sendonly' ||
        l === 'a=sendrecv' ||
        l === 'a=inactive' ||
        l === 'a=rtcp-mux' ||
        l === 'a=rtcp-rsize'
      ) {
        out.push(l)
      } else if (l.startsWith('a=ssrc:')) {
        if (l.includes(' cname:') || l.includes(' msid:')) {
          out.push(l)
        }
      } else if (l.startsWith('a=rtpmap:') || l.startsWith('a=fmtp:')) {
        const pt = l.split(':')[1]?.split(' ')[0]
        if (pt && keepPts.has(pt)) out.push(l)
      } else if (l.startsWith('a=candidate:')) {
        const parts = l.split(' ')
        const ip = parts[4] || ''
        const isIpv6 = ip.includes(':')
        if (keptCandidates < 4 && /\budp\b/i.test(l) && !isIpv6) {
          out.push(l)
          keptCandidates++
        }
      }
    }
  }

  for (const l of lines) {
    if (l.startsWith('m=')) {
      flushSection()
      sectionLines = [l]
      section = l.startsWith('m=video') ? 'video' : l.startsWith('m=audio') ? 'audio' : 'other'
    } else {
      sectionLines.push(l)
    }
  }
  flushSection()
  return out.join('\r\n') + '\r\n'
}

async function encodeSignal(payload: SignalPayload): Promise<string> {
  const slimPayload: SignalPayload = { ...payload, s: minifySdp(payload.s) }
  const json = JSON.stringify(slimPayload)
  const rawBytes = new TextEncoder().encode(json)
  const compressed = await compressDeflateRaw(rawBytes)
  if (compressed && compressed.length < rawBytes.length) {
    return `z.${bytesToBase64Url(compressed)}`
  }
  return `r.${bytesToBase64Url(rawBytes)}`
}

async function decodeSignal(code: string): Promise<SignalPayload> {
  const trimmed = code.trim()
  const rawToken = trimmed.includes('#o=')
    ? trimmed.split('#o=')[1].split('&')[0]
    : trimmed.includes('#offer=')
      ? trimmed.split('#offer=')[1].split('&')[0]
      : trimmed.includes('#answer=')
        ? trimmed.split('#answer=')[1].split('&')[0]
        : trimmed

  let jsonBytes: Uint8Array
  if (rawToken.startsWith('z.')) {
    jsonBytes = await decompressDeflateRaw(base64UrlToBytes(rawToken.slice(2)))
  } else if (rawToken.startsWith('r.')) {
    jsonBytes = base64UrlToBytes(rawToken.slice(2))
  } else {
    // Kompatibel mundur dengan format tanpa prefix
    jsonBytes = base64UrlToBytes(rawToken)
  }

  const json = new TextDecoder().decode(jsonBytes)
  const parsed = JSON.parse(json) as Partial<SignalPayload>
  if ((parsed.t !== 'offer' && parsed.t !== 'answer') || typeof parsed.s !== 'string' || !parsed.s) {
    throw new Error('Format kode pairing Wi-Fi tidak valid.')
  }
  return { t: parsed.t, s: parsed.s, n: parsed.n }
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

function waitForIceGathering(pc: RTCPeerConnection, timeoutMs = 1500): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    let settled = false
    const done = () => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', onState)
      resolve()
    }
    const onState = () => {
      if (pc.iceGatheringState === 'complete') done()
    }
    const timer = window.setTimeout(done, timeoutMs)
    pc.addEventListener('icegatheringstatechange', onState)
  })
}

export class WifiHostSession {
  private pc: RTCPeerConnection | null = null
  private remoteStream: MediaStream | null = null

  onStatus?: (status: WifiPairingStatus, message?: string) => void
  onStream?: (stream: MediaStream, label: string) => void

  async createOffer(): Promise<{ offerCode: string; shareUrl: string }> {
    this.close()
    this.onStatus?.('PREPARING')

    const pc = new RTCPeerConnection(RTC_CONFIG)
    this.pc = pc

    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.addTransceiver('audio', { direction: 'recvonly' })

    const incomingStream = new MediaStream()
    this.remoteStream = incomingStream

    pc.ontrack = (ev) => {
      const targetStream = ev.streams[0] ?? incomingStream
      if (!ev.streams[0]) {
        incomingStream.addTrack(ev.track)
      }
      this.remoteStream = targetStream
      this.onStream?.(targetStream, 'Kamera Wi-Fi')
      this.onStatus?.('CONNECTED')
    }

    pc.onconnectionstatechange = () => {
      const st = pc.connectionState
      if (st === 'connected') {
        this.onStatus?.('CONNECTED')
      } else if (st === 'failed' || st === 'disconnected') {
        this.onStatus?.('ERROR', 'Koneksi kamera Wi-Fi terputus. Buat sesi baru untuk menyambung ulang.')
      }
    }

    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    await waitForIceGathering(pc, 1500)

    const localSdp = pc.localDescription?.sdp
    if (!localSdp) {
      this.onStatus?.('ERROR', 'Gagal membuat SDP Offer WebRTC.')
      throw new Error('Gagal membuat SDP Offer WebRTC.')
    }

    const offerCode = await encodeSignal({ t: 'offer', s: localSdp })
    const base = `${window.location.origin}${window.location.pathname}`
    const shareUrl = `${base}?wifiNode=1#o=${offerCode}`
    this.onStatus?.('WAITING_ANSWER')
    return { offerCode, shareUrl }
  }

  async acceptAnswer(answerCode: string): Promise<void> {
    if (!this.pc) throw new Error('Belum ada sesi Host Wi-Fi aktif.')
    const parsed = await decodeSignal(answerCode)
    if (parsed.t !== 'answer') {
      throw new Error('Kode yang ditempel bukan Kode Answer dari HP Pengirim.')
    }
    this.onStatus?.('CONNECTING')
    await this.pc.setRemoteDescription({ type: 'answer', sdp: parsed.s })
  }

  close(): void {
    if (this.remoteStream) {
      this.remoteStream.getTracks().forEach((t) => t.stop())
      this.remoteStream = null
    }
    if (this.pc) {
      try {
        this.pc.close()
      } catch {
        // Abaikan
      }
      this.pc = null
    }
    this.onStatus?.('IDLE')
  }
}

export class WifiNodeSession {
  private pc: RTCPeerConnection | null = null

  onStatus?: (status: WifiPairingStatus, message?: string) => void

  async createAnswerFromOffer(offerCode: string, localStream: MediaStream): Promise<string> {
    this.close()
    this.onStatus?.('PREPARING')

    const parsed = await decodeSignal(offerCode)
    if (parsed.t !== 'offer') {
      throw new Error('Kode yang dimasukkan bukan Kode Offer dari Host.')
    }

    const pc = new RTCPeerConnection(RTC_CONFIG)
    this.pc = pc

    localStream.getTracks().forEach((track) => {
      pc.addTrack(track, localStream)
    })

    pc.onconnectionstatechange = () => {
      const st = pc.connectionState
      if (st === 'connected') {
        this.onStatus?.('CONNECTED')
      } else if (st === 'failed' || st === 'disconnected') {
        this.onStatus?.('ERROR', 'Koneksi ke Host Wi-Fi terputus.')
      }
    }

    await pc.setRemoteDescription({ type: 'offer', sdp: parsed.s })
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    await waitForIceGathering(pc, 1500)

    const localSdp = pc.localDescription?.sdp
    if (!localSdp) {
      this.onStatus?.('ERROR', 'Gagal membuat Kode Answer WebRTC.')
      throw new Error('Gagal membuat Kode Answer WebRTC.')
    }

    this.onStatus?.('WAITING_ANSWER')
    return encodeSignal({ t: 'answer', s: localSdp })
  }

  close(): void {
    if (this.pc) {
      try {
        this.pc.close()
      } catch {
        // Abaikan
      }
      this.pc = null
    }
    this.onStatus?.('IDLE')
  }
}

/**
 * Pengirim langsung dari Browser Web ke StreApp Android Host lewat WebSocket LAN
 * (`ws://<ip>:45679/ws?token=<token>`). Bekerja langsung di jaringan HTTP/LAN,
 * dan menyediakan tautan Jembatan LAN bawaan HP saat dibuka dari HTTPS (Vercel).
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
