# StreApp

Aplikasi Android untuk merekam dan menyiarkan gabungan beberapa sumber video dan audio dalam satu scene, seperti OBS versi ringkas di ponsel. Spesifikasi lengkap ada di `docs/PRD.md`. Urutan kerja ada di `docs/TASKS.md`. Baca keduanya sebelum mengerjakan tugas besar.

## Ketentuan produk (tidak bisa ditawar)

- Pemakaian pribadi, non-komersial, dipasang sebagai APK langsung. Bukan untuk toko aplikasi.
- Semua komponen gratis atau sumber terbuka. Tanpa watermark, iklan, akun pengguna, analitik pihak ketiga, SDK berbayar.
- Hanya Android. Minimum Android 10 (API 29). Uji utama di Android 12.
- Resolusi maksimal 1080p. Default 720p 30 fps pada kombinasi sumber yang berat.
- Semua fitur harus bisa jalan bersamaan (capture card, layar, kamera, Camera Node, semua audio), bukan memilih salah satu.
- Chat langsung hanya dilihat sendiri oleh pengguna. Satu-satunya pengecualian dari prinsip "tanpa akun" adalah chat YouTube, yang membutuhkan login Google atau kunci API. Pengecualian ini berlaku khusus untuk modul `chat`.

## Perangkat acuan

Android 12, Exynos 9825 dan Helio G99, RAM 4 GB. Helio G99 adalah batas bawah. Bila sebuah desain tidak masuk akal di perangkat ini, desain itu ditolak.

## Stack

- Bahasa: Kotlin. C++ (NDK) hanya bila hasil profiling membuktikan perlu.
- UI: Jetpack Compose, Material 3 yang dikustomisasi. Tema terang saja.
- Build: Gradle Kotlin DSL dengan version catalog (`gradle/libs.versions.toml`).
- Video: OpenGL ES 3.0 lewat EGL untuk penggabungan, MediaCodec hardware H.264 untuk encode, MediaMuxer untuk MP4.
- Kamera lokal: CameraX atau Camera2.
- Capture card dan webcam USB: pustaka UVC berbasis libuvc (misalnya UVCAndroid).
- Rekam layar: MediaProjection.
- Audio: AudioRecord untuk rekam, Oboe untuk monitoring latensi rendah. Mixer ditulis sendiri.
- Antar ponsel: WebRTC (SRT sebagai cadangan). Penemuan perangkat lewat QR dan NSD.
- Siaran: RTMP/RTMPS lewat RootEncoder atau implementasi sendiri.
- Test: JUnit untuk logika murni. Pipeline video dan USB diuji manual di perangkat.

Sebelum menambah pustaka baru, periksa lisensinya (hindari GPL, hindari yang butuh akun atau pembayaran) dan catat di `docs/LISENSI.md`.

## Struktur modul

```
app/                  UI Compose, navigasi, injeksi dependensi, service
core-model/           Scene, Source, Preset, Project (Kotlin murni, tanpa Android)
core-video/           Compositor OpenGL, encoder, muxer, pipeline rekam
core-audio/           Mixer, meter, monitoring, track audio
core-thermal/         Pemantau suhu dan kebijakan penurunan kualitas
source-uvc/           Capture card dan webcam USB
source-camera/        Kamera ponsel
source-screen/        Rekam layar
source-remote/        Camera Node lewat jaringan lokal (host dan node)
stream/               RTMP/RTMPS, profil akun platform
chat/                 Pembaca chat langsung per platform (hanya tampil di layar pengguna)
debug-overlay/        Overlay fps, drop frame, CPU, suhu, memori
```

Aturan dependensi: `core-model` tidak bergantung pada modul lain. Modul `source-*` hanya bergantung pada `core-model` dan `core-video` (untuk menyerahkan frame). `chat` hanya bergantung pada `core-model` dan `stream` (untuk profil akun). `app` boleh bergantung pada semuanya. Tidak ada dependensi melingkar.

## Aturan arsitektur (wajib)

1. Satu pipeline encode. Komposisi dilakukan sekali, di-encode sekali. Rekaman dan siaran memakai hasil encode yang sama.
2. Frame tidak boleh disalin ke CPU. Alirannya SurfaceTexture ke OpenGL ke surface encoder. Dilarang memakai Bitmap atau ByteArray per frame di jalur utama.
3. Setiap sumber mengimplementasikan antarmuka `Source` yang sama: `start()`, `stop()`, status (aktif, terputus, galat), dan menyediakan frame lewat surface atau tekstur.
4. Sumber yang tidak terlihat di scene aktif tidak dirender. Boleh tetap siaga bila perlu untuk perpindahan cepat.
5. Pencabutan kabel, putus jaringan, atau sumber gagal tidak boleh menghentikan rekaman. Sumber ditandai terputus dan mencoba tersambung ulang.
6. Audio diproses di thread khusus berprioritas tinggi. Tidak ada alokasi memori di dalam loop audio.
7. Rekaman harus bisa dipulihkan setelah crash (segmen berkala atau fragmented MP4).
8. Rekaman dan siaran berjalan di foreground service dengan tipe yang sesuai (mediaProjection, camera, microphone) dan notifikasi status.
9. Kebijakan termal berada di `core-thermal` dan hanya memberi perintah penurunan kualitas lewat satu antarmuka. Modul lain tidak membaca suhu sendiri.
10. Chat terpisah total dari pipeline video. Chat hanya dilihat pengguna: tidak dirender ke scene, tidak masuk compositor atau encoder, tidak terekam, tidak tersiar. Chat baca-saja (tanpa membalas atau moderasi), pesan tidak disimpan ke penyimpanan, memori dibatasi (sekitar 200 pesan terbaru), pembaruan UI dikelompokkan, dan panel berhenti memperbarui saat layar mati. Kegagalan atau putusnya chat tidak boleh memengaruhi rekaman atau siaran.

## Aturan performa

Target di perangkat acuan, profil Standar (capture card, kamera overlay, mic, audio capture card):

- CPU di bawah 40 persen, frame drop di bawah 1 persen dalam 60 menit.
- Memori puncak di bawah 1,2 GB.
- Tidak mencapai status termal severe dalam 60 menit pada suhu ruang normal.
- Sinkronisasi audio-video di bawah 50 ms.

Setiap perubahan pada jalur video atau audio harus menyebut dampaknya pada angka-angka ini, diukur lewat `debug-overlay`. Jangan mengklaim "lebih cepat" tanpa angka.

## Aturan UI

- Bersih, lapang, tema terang. Tanpa emoji di teks, label, notifikasi, dan komentar kode yang tampil ke pengguna.
- Tanpa warna neon. Palet: latar #F6F7F9, kartu #FFFFFF, teks #1F2933, teks sekunder #5B6670, aksen #3D6FA5, rekam #C0504D, berhasil #4F8A6B, peringatan #C98A2B, garis #E2E6EA.
- Ikon garis tipis yang konsisten. Ukuran sentuh minimal 48 dp.
- Status rekam tidak boleh bergantung pada warna saja (sertakan label dan bentuk).
- Bahasa: Indonesia dan Inggris lewat resource string. Tidak ada teks yang ditulis langsung di kode.

## Cara kerja agen

- Kerjakan satu tugas dari `docs/TASKS.md` sekali waktu. Jangan loncat ke tugas berikutnya tanpa diminta.
- Buat cabang git per tugas, commit kecil dengan pesan jelas.
- Sebelum menulis kode besar, tulis rencana singkat (file yang disentuh dan alasan), lalu tunggu persetujuan bila perubahannya menyentuh arsitektur.
- Perangkat tersambung lewat adb. Pasang dengan `./gradlew installDebug`, baca log dengan `adb logcat`. Jangan menebak perilaku perangkat; ukur.
- Tulis unit test untuk logika murni (`core-model`, mixer, kebijakan termal). Untuk pipeline video, USB, dan jaringan, siapkan langkah uji manual di PR dan minta saya melaporkan hasilnya.
- Bila terkena batasan platform (misalnya audio internal tidak diizinkan aplikasi tertentu), tampilkan penjelasan di UI dan catat di `docs/BATASAN.md`. Jangan membuat solusi sementara yang diam-diam salah.
- Bila tidak yakin soal spesifikasi, rujuk `docs/PRD.md` atau tanya. Jangan mengarang perilaku.

## Yang tidak boleh dilakukan

- Menambah fitur di luar PRD (filter lanjutan, plugin, editor video, dukungan iOS, 4K).
- Menambah pustaka berbayar, iklan, analitik, atau yang membutuhkan akun.
- Menyimpan stream key, token login, atau kunci API dalam teks biasa atau di dalam repo. Gunakan Android Keystore, dan jangan pernah melakukan commit kredensial.
- Memakai pustaka atau metode tidak resmi untuk membaca chat TikTok tanpa persetujuan saya. Tandai sebagai belum tersedia dan catat di `docs/BATASAN.md`.
- Mengirim data apa pun keluar perangkat selain siaran yang dimulai pengguna dan koneksi Camera Node yang dipasangkan.
- Mengubah target performa agar kode lolos.
