/**
 * Kanal signaling ringan terenkripsi End-to-End (AES-GCM 128-bit) di atas MQTT-over-WSS.
 * - Hanya digunakan selama ~1 detik untuk menukar Offer/Answer/ICE secara otomatis (1x scan QR).
 * - Kunci AES berada di fragment URL (#k=...) pada QR Code dan tidak pernah dikirim ke broker.
 * - Setelah WebRTC P2P tersambung, video & audio mengalir langsung antar perangkat di Wi-Fi lokal.
 */

const DEFAULT_BROKERS = [
  'wss://broker.emqx.io:8084/mqtt',
  'wss://broker.hivemq.com:8884/mqtt',
]

function bytesToBase64Url(bytes: Uint8Array): string {
  let bin = ''
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function base64UrlToBytes(str: string): Uint8Array {
  const padded = str.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((str.length + 3) % 4)
  const bin = atob(padded)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export function generateRoomCredentials(): { roomId: string; keyB64: string } {
  const roomBytes = new Uint8Array(7)
  const keyBytes = new Uint8Array(16)
  crypto.getRandomValues(roomBytes)
  crypto.getRandomValues(keyBytes)
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let roomId = ''
  for (let i = 0; i < roomBytes.length; i++) {
    roomId += alphabet[roomBytes[i] % alphabet.length]
  }
  return { roomId, keyB64: bytesToBase64Url(keyBytes) }
}

async function importAesKey(keyB64: string): Promise<CryptoKey> {
  const raw = new Uint8Array(base64UrlToBytes(keyB64))
  return crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])
}

async function encryptPayload(key: CryptoKey, obj: unknown): Promise<Uint8Array> {
  const iv = new Uint8Array(12)
  crypto.getRandomValues(iv)
  const plain = new TextEncoder().encode(JSON.stringify(obj))
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plain))
  const combined = new Uint8Array(12 + cipher.length)
  combined.set(iv, 0)
  combined.set(cipher, 12)
  return combined
}

async function decryptPayload(key: CryptoKey, data: Uint8Array): Promise<unknown> {
  if (data.length <= 12) throw new Error('Payload terlalu pendek')
  const iv = new Uint8Array(data.subarray(0, 12))
  const cipher = new Uint8Array(data.subarray(12))
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, cipher)
  return JSON.parse(new TextDecoder().decode(plain))
}

// --- Encoder / Decoder paket MQTT 3.1.1 minimal (tanpa dependensi eksternal) ---

function encodeVarInt(len: number): number[] {
  const out: number[] = []
  let x = len
  do {
    let digit = x % 128
    x = Math.floor(x / 128)
    if (x > 0) digit |= 0x80
    out.push(digit)
  } while (x > 0)
  return out
}

function encodeUtf8String(str: string): Uint8Array {
  const b = new TextEncoder().encode(str)
  const out = new Uint8Array(2 + b.length)
  out[0] = (b.length >> 8) & 0xff
  out[1] = b.length & 0xff
  out.set(b, 2)
  return out
}

function buildMqttPacket(headerByte: number, body: Uint8Array): Uint8Array {
  const rem = encodeVarInt(body.length)
  const out = new Uint8Array(1 + rem.length + body.length)
  out[0] = headerByte
  out.set(rem, 1)
  out.set(body, 1 + rem.length)
  return out
}

function buildConnectPacket(clientId: string): Uint8Array {
  const proto = encodeUtf8String('MQTT')
  const cid = encodeUtf8String(clientId)
  const body = new Uint8Array(proto.length + 4 + cid.length)
  body.set(proto, 0)
  let o = proto.length
  body[o++] = 4 // Protocol Level 4 (MQTT 3.1.1)
  body[o++] = 0x02 // Clean Session
  body[o++] = 0x00 // Keep Alive MSB
  body[o++] = 30   // Keep Alive 30s
  body.set(cid, o)
  return buildMqttPacket(0x10, body)
}

function buildSubscribePacket(packetId: number, topic: string): Uint8Array {
  const t = encodeUtf8String(topic)
  const body = new Uint8Array(2 + t.length + 1)
  body[0] = (packetId >> 8) & 0xff
  body[1] = packetId & 0xff
  body.set(t, 2)
  body[2 + t.length] = 0 // QoS 0
  return buildMqttPacket(0x82, body)
}

function buildPublishPacket(topic: string, payload: Uint8Array): Uint8Array {
  const t = encodeUtf8String(topic)
  const body = new Uint8Array(t.length + payload.length)
  body.set(t, 0)
  body.set(payload, t.length)
  return buildMqttPacket(0x30, body) // QoS 0, non-retained
}

export interface SignalEnvelope {
  from: 'host' | 'node'
  type: 'ready' | 'offer' | 'answer' | 'ice' | 'bye'
  sdp?: string
  candidate?: RTCIceCandidateInit
  name?: string
}

export class EncryptedSignalChannel {
  private ws: WebSocket | null = null
  private aesKey: CryptoKey | null = null
  private topic: string
  private role: 'host' | 'node'
  private keyB64: string
  private closed = false
  private rxBuf = new Uint8Array(0)
  private pingTimer: number | null = null
  private brokers: string[]

  onMessage?: (msg: SignalEnvelope) => void
  onError?: (reason: string) => void

  constructor(roomId: string, keyB64: string, role: 'host' | 'node', customBrokerUrl?: string) {
    this.topic = `streapp/sig/v1/${roomId}`
    this.keyB64 = keyB64
    this.role = role
    this.brokers = customBrokerUrl?.trim() ? [customBrokerUrl.trim(), ...DEFAULT_BROKERS] : DEFAULT_BROKERS
  }

  async connect(): Promise<void> {
    this.closed = false
    this.aesKey = await importAesKey(this.keyB64)

    let lastErr: Error | null = null
    for (const url of this.brokers) {
      if (this.closed) return
      try {
        await this.connectSingleBroker(url)
        return
      } catch (e) {
        lastErr = e instanceof Error ? e : new Error(String(e))
      }
    }
    throw lastErr ?? new Error('Gagal terhubung ke relay signaling WebRTC.')
  }

  private connectSingleBroker(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
      let settled = false
      const ws = new WebSocket(url, ['mqtt'])
      ws.binaryType = 'arraybuffer'
      this.ws = ws
      this.rxBuf = new Uint8Array(0)

      const timeout = window.setTimeout(() => {
        if (!settled) {
          settled = true
          try { ws.close() } catch {}
          reject(new Error(`Timeout menyambung ke ${url}`))
        }
      }, 6000)

      ws.onopen = () => {
        const cid = `sa-${this.role}-${Math.random().toString(36).slice(2, 10)}`
        ws.send(buildConnectPacket(cid))
      }

      ws.onmessage = (ev) => {
        if (!(ev.data instanceof ArrayBuffer)) return
        const chunk = new Uint8Array(ev.data)
        const merged = new Uint8Array(this.rxBuf.length + chunk.length)
        merged.set(this.rxBuf, 0)
        merged.set(chunk, this.rxBuf.length)
        this.rxBuf = merged
        this.parseIncoming(
          () => {
            if (!settled) {
              settled = true
              window.clearTimeout(timeout)
              this.startPing()
              resolve()
            }
          },
          (err) => {
            if (!settled) {
              settled = true
              window.clearTimeout(timeout)
              reject(err)
            }
          }
        )
      }

      ws.onerror = () => {
        if (!settled) {
          settled = true
          window.clearTimeout(timeout)
          reject(new Error(`Gagal membuka WebSocket ${url}`))
        }
      }

      ws.onclose = () => {
        this.stopPing()
        if (!settled) {
          settled = true
          window.clearTimeout(timeout)
          reject(new Error('Koneksi signaling ditutup lebih awal.'))
        }
      }
    })
  }

  private parseIncoming(onReady: () => void, onFail: (err: Error) => void): void {
    let offset = 0
    while (offset + 2 <= this.rxBuf.length) {
      const header = this.rxBuf[offset]
      let mult = 1
      let remLen = 0
      let pos = offset + 1
      let completeVar = false
      while (pos < this.rxBuf.length) {
        const byte = this.rxBuf[pos++]
        remLen += (byte & 0x7f) * mult
        if ((byte & 0x80) === 0) {
          completeVar = true
          break
        }
        mult *= 128
        if (mult > 128 * 128 * 128) break
      }
      if (!completeVar || pos + remLen > this.rxBuf.length) break

      const packetType = (header >> 4) & 0x0f
      const body = this.rxBuf.subarray(pos, pos + remLen)
      offset = pos + remLen

      if (packetType === 2) {
        // CONNACK
        if (body.length >= 2 && body[1] === 0) {
          this.ws?.send(buildSubscribePacket(1, this.topic))
        } else {
          onFail(new Error('Broker menolak koneksi MQTT.'))
        }
      } else if (packetType === 9) {
        // SUBACK -> kanal siap!
        onReady()
      } else if (packetType === 3) {
        // PUBLISH
        if (body.length >= 2) {
          const tLen = (body[0] << 8) | body[1]
          const qos = (header >> 1) & 0x03
          const payStart = 2 + tLen + (qos > 0 ? 2 : 0)
          if (payStart <= body.length) {
            const encrypted = body.subarray(payStart)
            void this.handleEncryptedBytes(encrypted)
          }
        }
      }
    }
    if (offset > 0) {
      this.rxBuf = this.rxBuf.subarray(offset)
    }
  }

  private async handleEncryptedBytes(bytes: Uint8Array): Promise<void> {
    if (!this.aesKey || this.closed) return
    try {
      const parsed = (await decryptPayload(this.aesKey, bytes)) as SignalEnvelope
      if (!parsed || parsed.from === this.role) return
      this.onMessage?.(parsed)
    } catch {
      // Abaikan pesan yang bukan untuk kunci sesi ini
    }
  }

  async send(msg: Omit<SignalEnvelope, 'from'>): Promise<void> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.aesKey || this.closed) return
    const encrypted = await encryptPayload(this.aesKey, { ...msg, from: this.role })
    this.ws.send(buildPublishPacket(this.topic, encrypted))
  }

  private startPing(): void {
    this.stopPing()
    this.pingTimer = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(new Uint8Array([0xc0, 0x00])) // PINGREQ
      }
    }, 20_000)
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      window.clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  close(): void {
    this.closed = true
    this.stopPing()
    if (this.ws) {
      const s = this.ws
      this.ws = null
      try { s.close() } catch {}
    }
  }
}
