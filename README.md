<p align="center">
  <img src="mobile/app/src/main/res/drawable/ic_launcher_foreground.png" alt="StreApp Logo" width="140" />
</p>

<h1 align="center">StreApp</h1>

<p align="center">
  <strong>Studio rekam dan siaran multi-sumber open-source yang terinspirasi dari OBS — tersedia untuk Android (<code>mobile/</code>) dan Browser (<code>web/</code>).</strong>
</p>

<p align="center">
  <a href="#struktur-monorepo">Struktur Repo</a> •
  <a href="#unduh-aplikasi-apk">Unduh APK Android</a> •
  <a href="#streapp-web">StreApp Web</a> •
  <a href="#fitur-utama">Fitur Utama</a> •
  <a href="#cara-build-dari-source">Build dari Source</a> •
  <a href="#alur-pengembangan-dan-bantuan-ai">Pengembangan AI</a> •
  <a href="#lisensi">Lisensi</a>
</p>

---

## Ringkasan

**StreApp** adalah studio produksi video dan audio yang terinspirasi dari OBS Studio untuk perangkat **Android** dan **Web**: menggabungkan feed capture card USB (UVC), rekam layar, kamera lokal, kamera dari perangkat kedua (*Camera Node* via Wi-Fi / Hotspot / Kabel USB / WebRTC), overlay teks/gambar/video loop, serta mixer multi-sumber audio ke dalam satu scene.

Proyek ini sepenuhnya gratis dan sumber terbuka: **tanpa watermark, tanpa iklan, tanpa akun paksaan, tanpa analitik pihak ketiga, dan tanpa SDK berbayar.**

---

## Struktur Monorepo

Repositori ini menyatukan aplikasi Android dan versi Web agar protokol pairing 2 arah, fitur, dan desain selalu sinkron:

```text
StreApp/
├── mobile/                 # Aplikasi Android (Kotlin, Jetpack Compose, OpenGL ES 3.0, Gradle)
├── web/                    # Aplikasi Web (Vue 3, TypeScript, Vite, 100% client-side, siap Vercel)
├── docs/                   # Spesifikasi PRD, daftar tugas, lisensi pustaka, & catatan batasan
├── .github/workflows/      # CI/CD otomatis rilis APK Android (build-apk.yml) & cek Web (check-web.yml)
├── AGENTS.md               # Pedoman arsitektur dan standar kode proyek
├── LICENSE                 # Lisensi Apache-2.0
└── README.md
```

---

## Unduh Aplikasi (APK)

Pengguna Android dapat langsung mengunduh dan memasang file `.apk` tanpa perlu mengompilasi kode sumber:

> **Unduh Langsung:** Buka halaman **[GitHub Releases](https://github.com/AlvinShofa13/StreApp/releases/latest)** lalu unduh berkas **`StreApp-android.apk`**.

### Cara Pemasangan di Android
1. Unduh **`StreApp-android.apk`** dari halaman **Releases** di sisi kanan repositori GitHub ini.
2. Buka berkas `.apk` yang sudah diunduh pada ponsel Android Anda.
3. Bila diminta oleh sistem, izinkan opsi **Pasang aplikasi tidak dikenal** (*Install unknown apps*) untuk browser atau manajer berkas Anda.
4. Selesaikan pemasangan dan buka **StreApp**.

### Otomatisasi Rilis APK di GitHub
Repositori ini dilengkapi workflow GitHub Actions ([`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)) yang membangun proyek dari folder `mobile/`. Setiap kali Anda membuat tag versi baru (misalnya `git tag v1.1.8 && git push origin main --tags`), GitHub akan otomatis menjalankan unit test, membangun APK, dan melampirkannya pada halaman **Releases** sebagai **`StreApp-android.apk`**.

---

## StreApp Web

Versi browser berada di folder [`web/`](web/) (lihat [`web/README.md`](web/README.md)), dibangun dengan **Vue 3 + TypeScript + Vite**, 100% *client-side* tanpa database:

- **Deploy ke Vercel:** Impor repositori GitHub ini di [Vercel](https://vercel.com), lalu atur **Root Directory** menjadi **`web`** (Framework Preset: `Vite`, Build Command: `npm run build`, Output Directory: `dist`).
- **Koneksi 2 Arah Web <-> Android:**
  - **HP Android -> StreApp Web:** Di StreApp Web klik **Tampilkan QR (Host Wi-Fi)**, lalu pindai QR dari aplikasi **StreApp Android (Pindai QR)**.
  - **StreApp Web -> HP Android:** Di StreApp Android klik **Tampilkan QR (Host)**, lalu pindai dari **StreApp Web (Mode Pengirim)** atau buka tautan Jembatan LAN HTTP yang tampil di HP Android.

---

## Fitur Utama

### Video & Kanvas Scene Interaktif
- **Multi-Sumber Bersamaan:** Gabungkan Capture Card USB (UVC), Rekam Layar, Kamera Depan/Belakang, Camera Node (ponsel kedua lewat Wi-Fi, Hotspot, atau Kabel USB Tethering), serta Overlay Teks, Gambar/Logo, dan **Overlay Video Loop** dalam satu scene.
- **Kanvas Scene Interaktif:** Ketuk sumber langsung pada pratinjau untuk memindah posisi (dengan *snap* otomatis), menarik sudut untuk mengatur ukuran, memilih orientasi kamera (**Potret 9:16** / **Lanskap 16:9**), mengatur urutan layer, serta memilih bentuk (**Persegi**, **Sudut Membulat**, **Bulat**).
- **Scene & Template Cepat:** Buat, ganti nama, gandakan, dan pindah antar scene dengan transisi **Potong Langsung (Cut)** atau **Pudar 0,3 detik (Fade)**.
- **Preset & Orientasi Output:** Mendukung 480p, 720p, dan 1080p pada 30 fps maupun 60 fps (**Lanskap 16:9** atau **Potret 9:16**).
- **Zero-Copy GPU Pipeline (Android):** Seluruh komposisi berjalan di OpenGL ES 3.0 (`SurfaceTexture` -> OpenGL -> `MediaCodec` H.264) tanpa penyalinan frame ke CPU, dengan satu pipeline encode bersama untuk rekaman dan siaran.
- **Proyektor Multi-Monitor (Web):** Mendukung pratinjau layar penuh dan jendela proyektor terpisah pada monitor kedua melalui *Window Management API*.

### Audio Mixer & Monitoring
- **Multi-Input Audio Terpisah:** Mikrofon ponsel/PC, headset kabel/Bluetooth, audio USB capture card, audio internal/tab, dan audio dari Camera Node.
- **Proteksi Anti-Feedback Otomatis:** Mencegah bunyi dengung saat monitoring mikrofon tanpa headphone, sambil tetap memungkinkan monitoring langsung audio capture card USB.

### Ketahanan, Siaran, & Chat Privat
- **Pemulihan Rekaman (Crash-Safe):** Di Android, rekaman MP4 ditulis tersegmentasi berkala dan otomatis digabung secara *lossless* menjadi satu berkas utuh saat berhenti.
- **Manajemen Termal & Estimasi Beban:** Memantau status termal perangkat dan menampilkan estimasi beban sebelum merekam.
- **Live Streaming RTMP/RTMPS (Android):** Mendukung profil akun YouTube, Twitch, dan TikTok dengan penyimpanan *stream key* terenkripsi di **Android Keystore**.
- **Panel Chat Privat (Baca-Saja):** Hanya tampil di layar pengguna (terpisah total dari kompositor video, tidak terekam, tidak tersiar).

---

## Arsitektur Modul Android (`mobile/`)

```text
mobile/
├── app/            UI Compose (tema terang), navigasi workspace, service, engine
├── core-model/     Model murni Scene, Source, Preset, Project, dan serialisasi JSON
├── core-video/     Kompositor OpenGL ES 3.0, encoder H.264, segmented MP4 muxer & merger
├── core-audio/     Mixer PCM khusus thread prioritas tinggi, meter, monitoring anti-feedback
├── core-thermal/   Pemantau suhu Android dan kebijakan penurunan kualitas adaptif
├── source-uvc/     Capture card dan webcam USB berbasis UVC (libuvc)
├── source-camera/  Kamera lokal ponsel (Camera2) dengan kontrol fokus/zoom/eksposur
├── source-screen/  Rekam layar ponsel melalui MediaProjection
├── source-remote/  Camera Node antar ponsel + QR pairing + Jembatan Web LAN
├── stream/         Publisher RTMP/RTMPS dan penyimpanan stream key terenkripsi
├── chat/           Pembaca chat langsung baca-saja (terpisah dari pipeline video)
└── debug-overlay/  Overlay diagnostik FPS ringkas dan telemetri
```

Dokumentasi spesifikasi dan daftar tugas pengembangan tersedia di [`docs/PRD.md`](docs/PRD.md) dan [`docs/TASKS.md`](docs/TASKS.md).

---

## Cara Build dari Source

### Android (`mobile/`)
Prasyarat: **JDK 25** (JetBrains Runtime di Android Studio) dan **Android SDK** (`compileSdk 37` / `minSdk 29`).

```bash
cd mobile

# 1. Jalankan seluruh unit test
./gradlew test

# 2. Bangun APK debug
./gradlew assembleDebug

# 3. Pasang langsung ke perangkat Android via USB (adb)
./gradlew installDebug
```

Hasil APK tersedia di `mobile/app/build/outputs/apk/debug/app-debug.apk`.

### Web (`web/`)
Prasyarat: **Node.js 20+** dan **npm**.

```bash
cd web

# 1. Pasang dependensi
npm install

# 2. Jalankan server pengembangan lokal
npm run dev

# 3. Bangun berkas produksi (type-check + Vite)
npm run build
```

---

## Privasi & Keamanan

- Seluruh pemrosesan video dan audio dilakukan secara lokal di perangkat; tidak ada pengunggahan otomatis maupun analitik pihak ketiga.
- *Stream key* dan kredensial API (bila digunakan pada modul chat YouTube) disimpan terenkripsi menggunakan **Android Keystore** dan tidak pernah ditulis ke dalam repositori.
- Koneksi Camera Node antar perangkat dienkripsi/divalidasi menggunakan token sesi sekali pakai dari kode QR.

---

## Alur Pengembangan dan Bantuan AI

Proyek ini dikembangkan secara bertahap dengan bantuan beberapa alat dan model AI pada tiap fase:

| Tahap | Peran / Cakupan | Alat & Model AI |
| --- | --- | --- |
| 1. Perancangan | Penyusunan spesifikasi dan dokumen (`PRD.md`, `design.md`, `AGENTS.md`) | Claude Sonnet 5.5 Medium |
| 2. Eksekusi Awal | Pembuatan fondasi proyek dan implementasi tahap awal | OpenCode — DeepSeek V4.1 Flash Free Default / OpenCode · max |
| 3. Eksekusi Pertengahan | Pengembangan lanjutan dan integrasi fitur tahap pertengahan | Gemini Flash 3.6 Agents (Android Studio) |
| 4. Eksekusi Akhir | Penyempurnaan pipeline, perbaikan performa, versi Web, pairing 2 arah, dan finalisasi | Antigravity App — Gemini 4 Neon High |

---

## Lisensi

Proyek ini dirilis di bawah lisensi **Apache License 2.0** (lihat [`LICENSE`](LICENSE)) dan hanya memanfaatkan komponen gratis serta berlisensi permisif (Apache-2.0, BSD, LGPL-2.1, MIT). Rincian lengkap seluruh pustaka pihak ketiga dan lisensinya tercatat di [`docs/LISENSI.md`](docs/LISENSI.md).
