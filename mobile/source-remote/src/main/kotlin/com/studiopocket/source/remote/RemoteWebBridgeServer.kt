package com.studiopocket.source.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Server HTTP + WebSocket (RFC 6455) ringan murni Kotlin di Android Host.
 * Memungkinkan StreApp Web / browser laptop di Wi-Fi yang sama mengirim feed kamera
 * atau tangkapan layar langsung ke kanvas StreApp Android (tervalidasi token pairing).
 */
class RemoteWebBridgeServer(
    private val preferredPort: Int = 45679,
    private val tokenProvider: () -> String,
) {
    var onWebFrame: ((ByteArray) -> Unit)? = null
    var onWebClientStatus: ((connected: Boolean, label: String) -> Unit)? = null

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    val localPort: Int get() = server?.localPort ?: preferredPort

    fun start(): Int {
        if (running) return localPort
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Throwable) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
        server = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "SP-WebBridgeAccept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Jembatan Web LAN aktif di port ${ss.localPort}")
        return ss.localPort
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handleClient(client) }, "SP-WebBridgeClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val fullTarget = parts[1]
            val path = fullTarget.substringBefore('?')
            val query = fullTarget.substringAfter('?', "")
            val params = parseQuery(query)

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val expectedToken = tokenProvider()
            val suppliedToken = params["token"].orEmpty()
            val isWs = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

            if (isWs && path == "/ws") {
                if (suppliedToken != expectedToken) {
                    sendHttp(output, "403 Forbidden", "text/plain; charset=utf-8", "Token tidak valid")
                    return
                }
                val wsKey = headers["sec-websocket-key"] ?: return
                val accept = computeWebSocketAccept(wsKey)
                val upgradeResp = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ").append(accept).append("\r\n\r\n")
                }
                output.write(upgradeResp.toByteArray(Charsets.US_ASCII))
                output.flush()
                readWebSocketLoop(socket, input, output)
            } else {
                // Layani halaman jembatan StreApp Web LAN bawaan
                val html = buildBridgeHtml(suppliedToken.ifBlank { "" })
                sendHttp(output, "200 OK", "text/html; charset=utf-8", html)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Koneksi Web Bridge berakhir: ${t.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readWebSocketLoop(socket: Socket, input: InputStream, output: OutputStream) {
        val label = "StreApp Web (${socket.inetAddress.hostAddress ?: "Wi-Fi"})"
        onWebClientStatus?.invoke(true, label)
        try {
            while (running) {
                val b1 = input.read()
                if (b1 < 0) break
                val b2 = input.read()
                if (b2 < 0) break

                val opcode = b1 and 0x0F
                val masked = (b2 and 0x80) != 0
                var len = (b2 and 0x7F).toLong()
                if (len == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi < 0 || lo < 0) break
                    len = ((hi shl 8) or lo).toLong()
                } else if (len == 127L) {
                    var l = 0L
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b < 0) return
                        l = (l shl 8) or (b.toLong() and 0xFF)
                    }
                    len = l
                }

                if (len < 0 || len > MAX_FRAME_BYTES) break

                val maskKey = if (masked) ByteArray(4).also { if (!readFully(input, it)) return } else null
                val payload = ByteArray(len.toInt())
                if (!readFully(input, payload)) break
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x02 -> { // Binary JPEG frame
                        if (payload.isNotEmpty()) {
                            onWebFrame?.invoke(payload)
                        }
                    }
                    0x08 -> break // Close
                    0x09 -> { // Ping -> reply Pong
                        output.write(byteArrayOf(0x8A.toByte(), 0x00))
                        output.flush()
                    }
                }
            }
        } finally {
            onWebClientStatus?.invoke(false, label)
        }
    }

    private fun computeWebSocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = try {
                input.read(buf, off, buf.size - off)
            } catch (_: EOFException) {
                return false
            }
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun sendHttp(output: OutputStream, status: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun buildBridgeHtml(initialToken: String): String {
        val safeToken = initialToken.replace("\"", "").replace("<", "").replace(">", "")
        return """
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>StreApp Web -> Android Bridge</title>
<style>
  body { margin:0; font-family: system-ui, sans-serif; background:#F6F7F9; color:#1F2933; padding:20px; }
  .wrap { max-width:680px; margin:0 auto; display:flex; flex-direction:column; gap:14px; }
  .card { background:#FFF; border:1px solid #E2E6EA; border-radius:14px; padding:16px; }
  h1 { margin:0 0 6px; font-size:18px; color:#3D6FA5; }
  p { margin:0; font-size:13px; color:#5B6670; }
  video { width:100%; border-radius:10px; background:#141C24; aspect-ratio:16/9; object-fit:contain; }
  .row { display:flex; gap:8px; flex-wrap:wrap; margin-top:10px; }
  input { flex:1; min-height:42px; border:1px solid #E2E6EA; border-radius:10px; padding:0 12px; font-size:13px; }
  button { min-height:44px; padding:0 16px; border-radius:10px; border:1px solid #E2E6EA; background:#FFF; color:#1F2933; font-weight:600; cursor:pointer; }
  button.primary { background:#3D6FA5; border-color:#3D6FA5; color:#FFF; }
  button.danger { border-color:#C0504D; color:#C0504D; }
  .status { font-size:13px; font-weight:600; color:#4F8A6B; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <h1>StreApp Web -> Android (Jembatan Wi-Fi LAN)</h1>
    <p>Kirim kamera webcam atau layar laptop/browser langsung ke kanvas StreApp di HP Android Anda lewat Wi-Fi yang sama.</p>
  </div>
  <div class="card">
    <video id="pv" autoplay muted playsinline></video>
    <div class="row">
      <input id="tok" value="$safeToken" placeholder="Token Pairing dari HP Android" />
      <button class="primary" onclick="startSource('camera')">Kirim Kamera / Webcam</button>
      <button class="primary" onclick="startSource('screen')">Kirim Layar / Tab</button>
      <button class="danger" onclick="stopAll()">Hentikan</button>
    </div>
    <p id="st" class="status" style="margin-top:10px;">Siap. Pilih Kamera atau Layar untuk mulai mengirim ke HP Android.</p>
  </div>
</div>
<script>
  let stream = null, ws = null, timer = null, busy = false;
  const pv = document.getElementById('pv');
  const st = document.getElementById('st');
  const tok = document.getElementById('tok');
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');

  async function startSource(kind) {
    stopAll();
    const token = tok.value.trim();
    if (!token) { st.textContent = 'Masukkan Token Pairing terlebih dahulu.'; return; }
    try {
      stream = kind === 'screen'
        ? await navigator.mediaDevices.getDisplayMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 24 } }, audio: false })
        : await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 24 } }, audio: false });
      pv.srcObject = stream;
      await pv.play();
      const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      ws = new WebSocket(wsProto + '//' + location.host + '/ws?token=' + encodeURIComponent(token));
      ws.binaryType = 'arraybuffer';
      st.textContent = 'Menyambungkan ke StreApp Android...';
      ws.onopen = () => {
        st.textContent = 'Tersambung! Video sedang dikirim langsung ke kanvas StreApp Android.';
        timer = setInterval(() => {
          if (!ws || ws.readyState !== 1 || busy || ws.bufferedAmount > 512000) return;
          if (pv.videoWidth <= 0 || pv.videoHeight <= 0) return;
          const scale = Math.min(1, 960 / Math.max(pv.videoWidth, pv.videoHeight));
          const w = Math.max(16, Math.round(pv.videoWidth * scale));
          const h = Math.max(16, Math.round(pv.videoHeight * scale));
          if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
          ctx.drawImage(pv, 0, 0, w, h);
          busy = true;
          canvas.toBlob((blob) => {
            if (blob && ws && ws.readyState === 1) {
              blob.arrayBuffer().then((b) => { if (ws && ws.readyState === 1) ws.send(b); busy = false; }).catch(() => { busy = false; });
            } else { busy = false; }
          }, 'image/jpeg', 0.74);
        }, 50);
      };
      ws.onerror = () => { st.textContent = 'Gagal menyambung. Periksa token atau koneksi Wi-Fi.'; };
      ws.onclose = () => { st.textContent = 'Koneksi ditutup.'; };
    } catch (e) {
      st.textContent = 'Gagal membuka sumber: ' + e.message;
    }
  }

  function stopAll() {
    if (timer) { clearInterval(timer); timer = null; }
    if (ws) { try { ws.close(); } catch {} ws = null; }
    if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null; }
    pv.srcObject = null;
    busy = false;
  }
</script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "SP-WebBridge"
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
