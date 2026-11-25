# Daftar Tugas StreApp

Urutan disusun dari risiko terbesar. Kerjakan berurutan. Tiap tugas punya kriteria selesai yang bisa diukur. Kolom "Diuji oleh" menunjukkan siapa yang memverifikasi: Agen (build, unit test, log) atau Saya (perangkat fisik, capture card, rasa panas).

Format laporan angka dari saya ke agen: perangkat, profil, fps aktual, drop frame, CPU, suhu, memori, durasi uji.

---

## Tahap 0. Fondasi

### T0.1 Proyek dan modul kosong
- Buat proyek Gradle Kotlin DSL dengan semua modul di `CLAUDE.md`, version catalog, minSdk 29, compileSdk stabil terbaru.
- Selesai bila: `./gradlew assembleDebug` sukses, aplikasi kosong terbuka di perangkat, dependensi antar modul sesuai aturan (tanpa siklus).
- Diuji oleh: Agen.

### T0.2 Debug overlay
- Modul `debug-overlay` menampilkan fps masuk dan keluar per sumber, drop frame, CPU proses, suhu baterai dan status termal, memori, serta durasi sesi. Bisa dinyalakan dan dimatikan, dan mencatat ke logcat tiap 5 detik.
- Selesai bila: overlay tampil di atas layar uji dan baris log berformat tetap muncul tiap 5 detik.
- Diuji oleh: Agen (build dan log), Saya (tampilan).

### T0.3 Model scene dan sumber
- `core-model`: `Source`, `Scene`, `Preset`, `Project`, penyimpanan ke JSON, antarmuka `Source` (start, stop, status, penyedia frame).
- Selesai bila: unit test untuk membuat, menggandakan, mengurutkan, menghapus scene, serta serialisasi dan deserialisasi proyek lulus.
- Diuji oleh: Agen.

---

## Tahap 1. Prototipe risiko (kerjakan dulu, sebelum UI)

### T1.1 Capture card tampil di preview
- `source-uvc`: deteksi perangkat USB, minta izin, buka stream, tampilkan ke surface preview. Format MJPEG dan YUV, 720p dan 1080p, 30 fps.
- Selesai bila: feed capture card tampil di Helio G99 pada 1080p 30 fps selama 10 menit, dengan fps aktual terlihat di overlay. Pencabutan kabel tidak membuat aplikasi crash dan sumber tersambung ulang saat dicolok lagi.
- Diuji oleh: Saya (capture card), Agen (log).
- Hasil: catat fps, CPU, suhu setelah 10 menit di `docs/HASIL_UJI.md`.

### T1.2 Kompositor OpenGL dengan tiga sumber
- `core-video`: render tiga sumber (UVC, kamera depan, satu sumber uji berupa pola) sebagai tekstur ke satu frame, posisi dan ukuran dapat diatur lewat kode.
- Selesai bila: keluaran gabungan tampil di preview pada 30 fps, tanpa penyalinan frame ke CPU (dibuktikan lewat profil: tidak ada alokasi Bitmap atau ByteArray per frame).
- Diuji oleh: Agen (tinjauan kode), Saya (perangkat).

### T1.3 Encode dan rekam gabungan
- Hubungkan kompositor ke MediaCodec H.264 dan MediaMuxer. Audio dari mic ikut direkam.
- Selesai bila: rekaman 30 menit pada profil Standar menghasilkan MP4 yang bisa diputar dengan audio dan video sinkron (selisih di bawah 50 ms), CPU di bawah 40 persen, tidak ada throttling berat. Hasil dicatat di `docs/HASIL_UJI.md`.
- Diuji oleh: Saya (perangkat dan pemutaran), Agen (log).
- Gerbang keputusan: bila angka tidak tercapai di Helio G99, hentikan dan diskusikan pengurangan target atau perubahan desain sebelum lanjut.

### T1.4 Pemulihan rekaman
- Rekaman ditulis berkala atau sebagai fragmented MP4 sehingga file tetap bisa diputar bila aplikasi dimatikan paksa.
- Selesai bila: aplikasi dimatikan paksa saat merekam dan file hasil masih bisa diputar sampai detik terakhir yang tertulis.
- Diuji oleh: Saya.

---

## Tahap 2. Audio

### T2.1 Sumber audio dan mixer
- `core-audio`: sumber mic ponsel, mic headset kabel dan Bluetooth, audio capture card (USB audio), audio internal (Android 10+ bila diizinkan). Fader, mute, dan meter level per sumber. Pencampuran ke satu track.
- Selesai bila: unit test mixer lulus (penjumlahan, limiter, mute, gain). Di perangkat, dua sumber audio terekam bersamaan dan terdengar benar. Peralihan headset otomatis dengan notifikasi.
- Diuji oleh: Agen (unit test), Saya (perangkat).

### T2.2 Monitoring headphone
- Tiga mode per sumber: mati, hanya monitor, monitor dan rekam. Peringatan umpan balik bila lewat speaker. Indikator latensi dan peringatan untuk Bluetooth.
- Selesai bila: suara sendiri terdengar di headphone kabel dengan latensi yang terukur dan tampil di UI, tanpa gangguan selama 10 menit.
- Diuji oleh: Saya.

### T2.3 Multi-track dan offset (P1)
- Beberapa track audio terpisah dalam satu file, penggeser offset per sumber.
- Selesai bila: file MP4 berisi lebih dari satu track audio yang bisa dipisahkan di pemutar, offset terdengar bergeser sesuai nilai.
- Diuji oleh: Saya.

---

## Tahap 3. Scene dan UI utama

### T3.1 Kerangka UI dan tema
- Tema terang sesuai palet, komponen dasar, navigasi enam layar, tanpa emoji, string lewat resource (Indonesia dan Inggris).
- Selesai bila: semua layar kosong tampil, kontras teks memenuhi WCAG AA, tidak ada string tertulis langsung di kode.
- Diuji oleh: Agen (lint), Saya (tampilan).

### T3.2 Layar utama dan perekaman
- Pratinjau, deretan scene, tombol rekam, indikator suhu dan baterai, durasi, sisa penyimpanan.
- Selesai bila: dari layar utama saya bisa memulai dan menghentikan rekaman, file tersimpan, dan notifikasi menunjukkan durasi serta lokasi.
- Diuji oleh: Saya.

### T3.3 Editor scene
- Geser dan ubah ukuran sumber dengan gestur, urutan layer, snap, bentuk overlay (persegi, bulat, sudut membulat).
- Selesai bila: scene Gameplay + Kamera Wajah bisa dibuat dari nol dalam kurang dari 1 menit, dan tersimpan serta terbuka kembali setelah aplikasi ditutup.
- Diuji oleh: Saya.

### T3.4 Template, preset, dan perpindahan scene
- Template bawaan, preset kualitas (Hemat, Seimbang, Tinggi), perpindahan scene saat merekam dengan cut dan fade 0,3 detik, tombol melayang.
- Selesai bila: berpindah scene saat merekam tidak menghentikan rekaman dan tidak ada frame hitam lebih dari 1 frame.
- Diuji oleh: Saya (periksa hasil rekaman).

---

## Tahap 4. Sumber lain dan manajemen termal

### T4.1 Rekam layar sebagai sumber
- `source-screen` dengan MediaProjection, resolusi dan fps dapat dipilih, kontrol melayang yang tidak ikut terekam, penjelasan bila audio internal tidak tersedia.
- Selesai bila: layar ponsel terekam bersama capture card dan kamera dalam satu scene pada profil Berat dengan default 720p 30 fps.
- Diuji oleh: Saya.

### T4.2 Kamera lokal dan kamera USB
- `source-camera` dan webcam UVC, kontrol fokus, eksposur, white balance, zoom, balik cermin.
- Selesai bila: kamera depan, belakang, dan webcam USB masing-masing bisa jadi sumber, dan kontrol fokus berfungsi.
- Diuji oleh: Saya.

### T4.3 Manajemen termal dan estimasi beban
- `core-thermal`: pantau status termal sistem, terapkan empat tahap penurunan (pratinjau, resolusi pratinjau, bitrate dan fps rekam, hentikan aman). Estimasi beban sebelum merekam dan peringatan untuk kombinasi berat.
- Selesai bila: unit test kebijakan termal lulus dengan data suhu tiruan, dan di perangkat kualitas turun bertahap saat panas tanpa rekaman terhenti sampai tahap kritis.
- Diuji oleh: Agen (unit test), Saya (perangkat).

### T4.4 Service latar depan dan penyimpanan
- Foreground service dengan tipe yang benar, peringatan penyimpanan hampir penuh dan penghentian aman.
- Selesai bila: rekaman 60 menit tidak dimatikan sistem dengan layar mati dan aplikasi di latar belakang.
- Diuji oleh: Saya.

---

## Tahap 5. Mode dua ponsel

### T5.1 Pairing dan Camera Node
- `source-remote`: HP A menampilkan QR, HP B memindai dan masuk mode Camera Node (pratinjau kecil, status, kontrol fokus lokal). Enkripsi dan token sekali pakai.
- Selesai bila: pairing berhasil pada jaringan Wi-Fi yang sama dan lewat hotspot, dan perangkat tidak dipasangkan ditolak.
- Diuji oleh: Saya (dua ponsel).

### T5.2 Aliran video Node ke Host
- Kirim video dengan WebRTC, muncul di Host sebagai sumber biasa. Mode Kualitas dan Latensi Rendah, bitrate adaptif, tampilan pengganti saat putus, sambung ulang hingga 30 detik.
- Selesai bila: latensi di bawah 250 ms pada Wi-Fi 5 GHz (diukur dengan stopwatch dan kamera), rekaman tidak berhenti saat Node dimatikan lalu dinyalakan lagi.
- Diuji oleh: Saya.

### T5.3 Kontrol jarak jauh dan audio Node
- Host mengatur fokus, zoom, eksposur, senter Node. Opsi audio mic Node ke Host.
- Selesai bila: kontrol berfungsi dan audio Node masuk ke mixer Host sebagai sumber terpisah.
- Diuji oleh: Saya.

### T5.4 Dua sampai tiga Node
- Dukung dua Node (wajib) dan tiga Node (bila hasil uji beban lolos), 720p 30 fps per Node.
- Selesai bila: dua Node dan capture card berjalan bersamaan pada profil Berat; hasil tiga Node dicatat di `docs/HASIL_UJI.md` dan diputuskan masuk atau tidak.
- Diuji oleh: Saya.

---

## Tahap 6. Siaran langsung (bersyarat)

### T6.1 RTMP ke satu platform
- `stream`: kirim hasil encode yang sama ke server RTMP/RTMPS. Mulai dari Twitch atau YouTube karena tanpa syarat akun yang ketat.
- Selesai bila: siaran uji 10 menit tampil di platform sambil merekam lokal pada saat yang sama, tanpa menambah encoder.
- Diuji oleh: Saya.

### T6.2 Profil akun dan template platform
- Template YouTube, Twitch, TikTok. Beberapa profil akun per platform dengan stream key tersimpan terenkripsi (Android Keystore). Pilih profil sebelum siaran. Profil yang belum memenuhi syarat ditandai belum siap.
- Selesai bila: dua profil TikTok (satu memenuhi syarat, satu belum) tersimpan, dipilih, dan siaran berjalan dengan akun yang memenuhi syarat.
- Diuji oleh: Saya.

### T6.3 Ketahanan siaran
- Bitrate adaptif, sambung ulang hingga 60 detik tanpa menghentikan rekaman, panel status (bitrate, drop frame, durasi).
- Selesai bila: jaringan dimatikan 30 detik lalu dinyalakan, siaran tersambung lagi dan rekaman lokal utuh. Siaran 60 menit di profil Standar tanpa throttling berat.
- Diuji oleh: Saya.
- Bila hasil tidak stabil di Helio G99, tandai fitur ini ditunda sesuai PRD.

### T6.4 Panel Chat (UI dengan data tiruan)
- Modul `chat` dan Panel Chat di `app`: panel di layar utama (potret) atau jendela melayang yang bisa digeser dan diubah ukurannya (lanskap), disembunyikan dengan satu ketukan. Pesan menampilkan nama, isi, dan penanda peran (teks atau ikon garis). Pengguliran otomatis yang berhenti saat pengguna menggulir ke atas, tombol kembali ke pesan terbaru, ukuran teks dapat diatur, batas sekitar 200 pesan, penyaring bot dan perintah opsional, dan indikator status sambungan.
- Kerjakan lebih dulu dengan sumber pesan tiruan (generator pesan acak) lewat antarmuka `ChatSource`, sehingga UI bisa diuji tanpa platform.
- Selesai bila: dengan 20 pesan per detik dari generator tiruan, panel tetap lancar, pembaruan UI dikelompokkan, memori tidak membengkak, panel berhenti memperbarui saat layar mati, dan panel tidak muncul di hasil rekaman maupun di siaran. Unit test untuk batas pesan dan penyaring lulus.
- Diuji oleh: Agen (unit test), Saya (tampilan dan hasil rekaman).

### T6.5 Chat Twitch
- Implementasi `ChatSource` untuk Twitch lewat koneksi WebSocket baca-saja anonim. Nama kanal disimpan di profil akun siaran (T6.2) dan panel menyambung otomatis saat siaran dimulai dengan profil tersebut. Sambung ulang otomatis dan status sambungan.
- Selesai bila: pesan chat kanal uji tampil real time di Panel Chat selama siaran 10 menit, tanpa login. Memutus lalu menyalakan jaringan membuat chat tersambung lagi, dan siaran serta rekaman tidak terganggu.
- Diuji oleh: Saya (kanal Twitch), Agen (log).

### T6.6 Chat YouTube
- Implementasi `ChatSource` untuk YouTube lewat YouTube Live Streaming API (pesan live chat). Perlu login Google atau kunci API, ini pengecualian yang sudah disetujui pada `CLAUDE.md`. Token disimpan di Android Keystore, polling mengikuti interval yang diberikan API, dan kuota dihitung serta ditampilkan di pengaturan.
- Selesai bila: pesan chat siaran YouTube tampil di Panel Chat selama 10 menit tanpa melampaui kuota, dan putus jaringan tidak mengganggu siaran atau rekaman.
- Diuji oleh: Saya (akun YouTube), Agen (log).

### T6.7 Penyelidikan chat TikTok (spike)
- Teliti apakah ada jalur resmi dan stabil untuk membaca chat TikTok dari sisi pembuat siaran. Tulis temuan di `docs/BATASAN.md`. Jangan mengimplementasikan pustaka tidak resmi tanpa persetujuan saya.
- Selesai bila: ada laporan singkat berisi jalur yang ditemukan, syarat akun, risiko, dan rekomendasi (kerjakan atau tandai belum tersedia).
- Diuji oleh: Agen (riset), Saya (keputusan).

---

## Tahap 7. Penyelesaian

### T7.1 Overlay teks dan gambar, penanda waktu (P1)
- Selesai bila: logo dan teks statis bisa ditambahkan ke scene, penanda waktu tersimpan di berkas pendamping.
- Diuji oleh: Saya.

### T7.2 Ekspor dan impor proyek (P1)
- Selesai bila: proyek diekspor ke berkas dan terbuka sama persis di perangkat lain.
- Diuji oleh: Saya.

### T7.3 Uji ketahanan akhir
- Sesi 60 menit pada profil Standar di Exynos 9825 dan Helio G99, dengan siaran dan Node aktif bila lolos tahap sebelumnya.
- Selesai bila: semua target di `CLAUDE.md` (CPU, drop frame, memori, termal, sinkronisasi) tercapai atau penyimpangannya dicatat di `docs/HASIL_UJI.md` beserta keputusan.
- Diuji oleh: Saya.

---

## Catatan untuk agen

- Setelah menyelesaikan tugas, ringkas apa yang diubah, cara menguji, dan angka yang diharapkan, lalu berhenti.
- Bila tugas gagal memenuhi kriteria, laporkan angka sebenarnya. Jangan menurunkan kriteria sendiri.
- Perbarui `docs/HASIL_UJI.md` dan `docs/BATASAN.md` setiap kali ada temuan baru.
