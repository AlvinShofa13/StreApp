/**
 * Generator QR Code ISO/IEC 18004 (Byte mode, Versi 1..40, ECC Level L/M) murni TypeScript
 * tanpa dependensi eksternal. Menghasilkan matriks modul siap render ke Canvas atau Data URL.
 */

export interface QrMatrix {
  size: number
  modules: Uint8Array // 1 = gelap, 0 = terang
}

// Jumlah codeword ECC per blok & jumlah blok untuk ECC Level L (cukup untuk layar digital bersih)
// Indeks 1..40 sesuai versi QR
const ECC_CODEWORDS_PER_BLOCK_L = [
  0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
  28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
]

const NUM_ERROR_CORRECTION_BLOCKS_L = [
  0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
  8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
]

function getNumRawDataModules(ver: number): number {
  let result = (16 * ver + 128) * ver + 64
  if (ver >= 2) {
    const numAlign = Math.floor(ver / 7) + 2
    result -= (25 * numAlign - 10) * numAlign - 55
    if (ver >= 7) result -= 36
  }
  return result
}

function getNumDataCodewordsL(ver: number): number {
  return (
    Math.floor(getNumRawDataModules(ver) / 8) -
    ECC_CODEWORDS_PER_BLOCK_L[ver] * NUM_ERROR_CORRECTION_BLOCKS_L[ver]
  )
}

function getAlignmentPatternPositions(ver: number): number[] {
  if (ver === 1) return []
  const numAlign = Math.floor(ver / 7) + 2
  const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (numAlign * 2 - 2)) * 2
  const result = [6]
  for (let pos = ver * 4 + 10; result.length < numAlign; pos -= step) {
    result.splice(1, 0, pos)
  }
  return result
}

function gfMultiply(x: number, y: number): number {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z
}

function reedSolomonDivisor(degree: number): Uint8Array {
  const result = new Uint8Array(degree)
  result[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = gfMultiply(result[j], root)
      if (j + 1 < result.length) result[j] ^= result[j + 1]
    }
    root = gfMultiply(root, 0x02)
  }
  return result
}

function reedSolomonRemainder(data: Uint8Array, divisor: Uint8Array): Uint8Array {
  const result = new Uint8Array(divisor.length)
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ result[0]
    result.copyWithin(0, 1)
    result[result.length - 1] = 0
    for (let j = 0; j < divisor.length; j++) {
      result[j] ^= gfMultiply(divisor[j], factor)
    }
  }
  return result
}

function addEccAndInterleave(data: Uint8Array, ver: number): Uint8Array {
  const numBlocks = NUM_ERROR_CORRECTION_BLOCKS_L[ver]
  const blockEccLen = ECC_CODEWORDS_PER_BLOCK_L[ver]
  const rawCodewords = Math.floor(getNumRawDataModules(ver) / 8)
  const numShortBlocks = numBlocks - (rawCodewords % numBlocks)
  const shortBlockLen = Math.floor(rawCodewords / numBlocks)

  const blocks: Uint8Array[] = []
  const rsDiv = reedSolomonDivisor(blockEccLen)
  let k = 0
  for (let i = 0; i < numBlocks; i++) {
    const datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1)
    const dat = data.subarray(k, k + datLen)
    k += datLen
    const ecc = reedSolomonRemainder(dat, rsDiv)
    const block = new Uint8Array(shortBlockLen + 1)
    block.set(dat, 0)
    if (i < numShortBlocks) {
      // Padding slot untuk menyamakan panjang saat interleave
      block.set(ecc, shortBlockLen - blockEccLen + 1)
    } else {
      block.set(ecc, shortBlockLen - blockEccLen + 1)
    }
    blocks.push(block)
  }

  const result = new Uint8Array(rawCodewords)
  let outIdx = 0
  for (let i = 0; i < shortBlockLen + 1; i++) {
    for (let j = 0; j < blocks.length; j++) {
      if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) {
        result[outIdx++] = blocks[j][i]
      }
    }
  }
  return result
}

function appendBits(val: number, len: number, out: number[]): void {
  for (let i = len - 1; i >= 0; i--) {
    out.push((val >>> i) & 1)
  }
}

export function encodeQr(text: string): QrMatrix {
  const bytes = new TextEncoder().encode(text)

  // Cari versi terkecil 1..40 yang muat menampung payload UTF-8
  let ver = 0
  for (let v = 1; v <= 40; v++) {
    const ccBits = v <= 9 ? 8 : 16
    const dataBits = 4 + ccBits + bytes.length * 8
    if (dataBits <= getNumDataCodewordsL(v) * 8) {
      ver = v
      break
    }
  }
  if (ver === 0) {
    throw new Error('Data terlalu panjang untuk QR Code.')
  }

  const dataCapBits = getNumDataCodewordsL(ver) * 8
  const bits: number[] = []
  appendBits(0b0100, 4, bits) // Byte mode
  appendBits(bytes.length, ver <= 9 ? 8 : 16, bits)
  for (let i = 0; i < bytes.length; i++) {
    appendBits(bytes[i], 8, bits)
  }
  appendBits(0, Math.min(4, dataCapBits - bits.length), bits)
  while (bits.length % 8 !== 0) bits.push(0)

  let padByte = 0xec
  while (bits.length < dataCapBits) {
    appendBits(padByte, 8, bits)
    padByte = padByte === 0xec ? 0x11 : 0xec
  }

  const dataCodewords = new Uint8Array(bits.length / 8)
  for (let i = 0; i < bits.length; i++) {
    dataCodewords[i >>> 3] |= bits[i] << (7 - (i & 7))
  }

  const allCodewords = addEccAndInterleave(dataCodewords, ver)
  const size = ver * 4 + 17
  const modules = new Uint8Array(size * size)
  const isFunction = new Uint8Array(size * size)

  const setFunc = (x: number, y: number, isDark: boolean) => {
    const idx = y * size + x
    modules[idx] = isDark ? 1 : 0
    isFunction[idx] = 1
  }

  // Timing patterns
  for (let i = 0; i < size; i++) {
    setFunc(6, i, i % 2 === 0)
    setFunc(i, 6, i % 2 === 0)
  }

  // Finder patterns (3 sudut)
  const drawFinder = (cx: number, cy: number) => {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const xx = cx + dx
        const yy = cy + dy
        if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
          const dist = Math.max(Math.abs(dx), Math.abs(dy))
          setFunc(xx, yy, dist !== 2 && dist !== 4)
        }
      }
    }
  }
  drawFinder(3, 3)
  drawFinder(size - 4, 3)
  drawFinder(3, size - 4)

  // Alignment patterns
  const alignPos = getAlignmentPatternPositions(ver)
  for (let i = 0; i < alignPos.length; i++) {
    for (let j = 0; j < alignPos.length; j++) {
      if (
        (i === 0 && j === 0) ||
        (i === 0 && j === alignPos.length - 1) ||
        (i === alignPos.length - 1 && j === 0)
      ) {
        continue
      }
      const cx = alignPos[i]
      const cy = alignPos[j]
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          setFunc(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
        }
      }
    }
  }

  // Reserve format bits (ditulis final setelah mask dipilih)
  const drawFormatBits = (mask: number) => {
    const eclFormatBits = 1 // Level L = 01
    const dataBits5 = (eclFormatBits << 3) | mask
    let rem = dataBits5
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
    const bits15 = ((dataBits5 << 10) | rem) ^ 0x5412

    for (let i = 0; i <= 5; i++) setFunc(8, i, ((bits15 >>> i) & 1) !== 0)
    setFunc(8, 7, ((bits15 >>> 6) & 1) !== 0)
    setFunc(8, 8, ((bits15 >>> 7) & 1) !== 0)
    setFunc(7, 8, ((bits15 >>> 8) & 1) !== 0)
    for (let i = 9; i < 15; i++) setFunc(14 - i, 8, ((bits15 >>> i) & 1) !== 0)

    for (let i = 0; i < 8; i++) setFunc(size - 1 - i, 8, ((bits15 >>> i) & 1) !== 0)
    for (let i = 8; i < 15; i++) setFunc(8, size - 15 + i, ((bits15 >>> i) & 1) !== 0)
    setFunc(8, size - 8, true) // Dark module wajib
  }
  drawFormatBits(0)

  // Version info (untuk versi >= 7)
  if (ver >= 7) {
    let rem = ver
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25)
    const bits18 = (ver << 12) | rem
    for (let i = 0; i < 18; i++) {
      const bit = ((bits18 >>> i) & 1) !== 0
      const a = size - 11 + (i % 3)
      const b = Math.floor(i / 3)
      setFunc(a, b, bit)
      setFunc(b, a, bit)
    }
  }

  // Tempatkan bit data zig-zag dari kanan bawah
  let bitIdx = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j
        const upward = ((right + 1) & 2) === 0
        const y = upward ? size - 1 - vert : vert
        const idx = y * size + x
        if (!isFunction[idx] && bitIdx < allCodewords.length * 8) {
          modules[idx] = ((allCodewords[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1)
          bitIdx++
        }
      }
    }
  }

  // Terapkan mask 0..7 dan pilih penalti terendah (standar ISO)
  const applyMask = (mask: number) => {
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const idx = y * size + x
        if (isFunction[idx]) continue
        let invert = false
        switch (mask) {
          case 0: invert = (x + y) % 2 === 0; break
          case 1: invert = y % 2 === 0; break
          case 2: invert = x % 3 === 0; break
          case 3: invert = (x + y) % 3 === 0; break
          case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break
          case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break
          case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break
          case 7: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; break
        }
        if (invert) modules[idx] ^= 1
      }
    }
  }

  const penaltyScore = (): number => {
    let score = 0
    // Penalti 1: 5+ modul sejajar warna sama
    for (let y = 0; y < size; y++) {
      let runColor = -1
      let runLen = 0
      for (let x = 0; x < size; x++) {
        const c = modules[y * size + x]
        if (c === runColor) {
          runLen++
          if (runLen === 5) score += 3
          else if (runLen > 5) score += 1
        } else {
          runColor = c
          runLen = 1
        }
      }
    }
    for (let x = 0; x < size; x++) {
      let runColor = -1
      let runLen = 0
      for (let y = 0; y < size; y++) {
        const c = modules[y * size + x]
        if (c === runColor) {
          runLen++
          if (runLen === 5) score += 3
          else if (runLen > 5) score += 1
        } else {
          runColor = c
          runLen = 1
        }
      }
    }
    // Penalti 2: blok 2x2 warna sama
    for (let y = 0; y < size - 1; y++) {
      for (let x = 0; x < size - 1; x++) {
        const c = modules[y * size + x]
        if (
          c === modules[y * size + x + 1] &&
          c === modules[(y + 1) * size + x] &&
          c === modules[(y + 1) * size + x + 1]
        ) {
          score += 3
        }
      }
    }
    return score
  }

  let bestMask = 0
  let minPenalty = Number.MAX_SAFE_INTEGER
  for (let m = 0; m < 8; m++) {
    applyMask(m)
    drawFormatBits(m)
    const p = penaltyScore()
    if (p < minPenalty) {
      minPenalty = p
      bestMask = m
    }
    applyMask(m) // Undo mask
  }

  applyMask(bestMask)
  drawFormatBits(bestMask)
  return { size, modules }
}

/**
 * Merender teks menjadi Data URL gambar PNG QR Code tajam (dengan quiet zone 4 modul).
 */
export function renderQrToDataUrl(text: string, targetSizePx = 360): string {
  const { size, modules } = encodeQr(text)
  const quiet = 4
  const totalModules = size + quiet * 2
  const scale = Math.max(2, Math.floor(targetSizePx / totalModules))
  const canvasSize = totalModules * scale

  const canvas = document.createElement('canvas')
  canvas.width = canvasSize
  canvas.height = canvasSize
  const ctx = canvas.getContext('2d')
  if (!ctx) return ''

  ctx.fillStyle = '#FFFFFF'
  ctx.fillRect(0, 0, canvasSize, canvasSize)
  ctx.fillStyle = '#111827'

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (modules[y * size + x]) {
        ctx.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale)
      }
    }
  }
  return canvas.toDataURL('image/png')
}
