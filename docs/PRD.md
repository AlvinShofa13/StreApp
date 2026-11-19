# PRD - Aplikasi Mobile Perekam dan Compositor Multi-Sumber

Versi 1.1 | Status: Draft, keputusan produk sudah dimasukkan | Nama kerja produk: StudioPocket (dapat diganti)

## 1. Ringkasan Produk

StudioPocket adalah aplikasi mobile yang membawa konsep inti OBS Studio ke ponsel: menggabungkan beberapa sumber video dan audio dalam satu tampilan (scene), lalu merekamnya menjadi satu file. Aplikasi ini dirancang untuk kreator yang menangkap gameplay dari konsol atau PC melalui capture card, merekam layar ponsel, menambahkan kamera wajah, dan merekam suara mic secara bersamaan, tanpa perlu laptop.

Fokus produk adalah fitur inti yang benar-benar dipakai, antarmuka yang bersih dan terang, serta konsumsi daya dan panas yang rendah. Fitur OBS yang kompleks (plugin, filter lanjutan, transisi rumit, scripting) sengaja tidak dimasukkan.

Ketentuan pemakaian: aplikasi ini untuk pemakaian pribadi di perangkat milik sendiri dan bukan produk komersial. Semua komponen memakai teknologi gratis atau sumber terbuka, tanpa watermark, tanpa iklan, tanpa akun, dan tanpa layanan berbayar.

## 2. Latar Belakang dan Masalah

- OBS tidak tersedia secara native di ponsel dan memerlukan komputer.
- Kreator mobile yang memakai capture card biasanya hanya mendapat aplikasi bawaan yang terbatas: satu sumber, tanpa mixer audio, tanpa scene.
- Perekaman layar bawaan ponsel tidak bisa digabung dengan feed capture card dan kamera sekaligus.
- Aplikasi multi-sumber yang ada cenderung membuat ponsel panas dan cepat menguras baterai.

## 3. Tujuan dan Non-Tujuan

### Tujuan

1. Merekam beberapa sumber video dan audio secara bersamaan dalam satu file hasil.
2. Menyediakan scene dengan preset yang berbeda-beda dan dapat berpindah antar scene saat merekam.
3. Mendukung pemakaian beberapa ponsel: satu perekam utama dan dua sampai tiga kamera fokus.
4. Antarmuka bersih, berwarna terang, tanpa emoji, tanpa warna neon.
5. Stabil dan hemat: sesi rekam 60 menit tanpa throttling berat pada perangkat acuan (Android 12, Helio G99, RAM 4 GB).
6. Live streaming langsung ke YouTube, TikTok, dan Twitch tanpa layanan berbayar, bila terbukti stabil.
7. Gratis untuk pemakaian pribadi: tanpa watermark, tanpa iklan, tanpa akun, semua komponen gratis atau sumber terbuka.

### Non-Tujuan (versi 1.0)

- Layanan streaming berbayar, server relay, atau siaran ke banyak platform sekaligus.
- Plugin, filter chroma key lanjutan, skrip, dan docks khusus.
- Editor video pasca-rekam.
- Dukungan iOS, desktop, atau web.
- Resolusi di atas 1080p (4K).
- Distribusi komersial, iklan, akun pengguna, dan fitur berbayar.
- Chat yang tampil di rekaman atau siaran, chat dibacakan dengan suara, serta membalas atau memoderasi chat.

## 4. Target Pengguna

| Persona | Kebutuhan utama |
| --- | --- |
| Kreator gameplay mobile/konsol | Capture card + suara mic + kamera wajah dalam satu rekaman |
| Pembuat tutorial dan review | Rekam layar HP + narasi mic + kamera tangan/wajah |
| Pembicara/edukator | Beberapa scene (slide, kamera, gabungan) dengan perpindahan cepat |
| Pengguna dua ponsel | Satu HP merekam, HP kedua menjadi kamera dengan fokus pada objek |

## 5. Platform dan Batasan Teknis

Batasan platform memengaruhi cakupan fitur, sehingga perlu diputuskan sejak awal.

| Aspek | Android | iOS |
| --- | --- | --- |
| Capture card (video) | Didukung via USB Video Class (UVC) pada perangkat dengan USB OTG/USB-C | Terbatas; kamera eksternal UVC tersedia pada model USB-C dan versi iOS terbaru, perlu validasi per perangkat |
| Audio dari capture card | Via USB Audio Class, bergantung dukungan perangkat | Terbatas, perlu validasi |
| Rekam layar | MediaProjection | ReplayKit (berjalan sebagai ekstensi dengan batas memori ketat) |
| Audio internal ponsel | Didukung Android 10+ (tidak semua aplikasi mengizinkan) | Hanya audio aplikasi lewat ReplayKit |
| Encoder | Hardware (MediaCodec) | Hardware (VideoToolbox) |

Keputusan: aplikasi hanya untuk Android, iOS tidak dikerjakan pada tahap ini. Versi minimum Android 10 (diperlukan untuk audio internal), dengan pengujian utama pada Android 12 dan perangkat dengan USB-C atau OTG.

Perangkat acuan untuk seluruh target performa:

| Komponen | Spesifikasi | Catatan |
| --- | --- | --- |
| Sistem operasi | Android 12 | Versi uji utama |
| Chipset | Exynos 9825 dan Helio G99 | Helio G99 menjadi batas bawah karena ruang termalnya paling sempit |
| RAM | 4 GB | Anggaran memori aplikasi di bawah 1,2 GB |

Karena Helio G99 dan RAM 4 GB adalah batas bawah, target kombinasi sumber disusun bertingkat (lihat bagian 9.1) dan 720p 30 fps menjadi default pada kombinasi berat.

## 6. Konsep Inti

- Sumber (Source): satu input video atau audio, misalnya capture card, layar, kamera depan, kamera eksternal, kamera dari HP kedua, mic, audio capture card.
- Scene: kumpulan sumber dengan posisi, ukuran, dan pengaturan audio tertentu. Satu proyek dapat memiliki banyak scene.
- Preset: konfigurasi tersimpan (resolusi, frame rate, bitrate, tata letak, level audio) yang dapat dipasang ke scene.
- Mixer: pengatur volume, mute, dan monitoring untuk setiap sumber audio.
- Compositor: mesin penggabung semua sumber video menjadi satu frame keluaran.

## 7. Kebutuhan Fungsional

Prioritas: P0 wajib untuk rilis 1.0, P1 sebaiknya ada, P2 fase berikutnya.

### 7.1 Input Video dari Capture Card (P0)

- FR-1.1 Mendeteksi capture card USB secara otomatis saat dicolok, menampilkan notifikasi perangkat terdeteksi.
- FR-1.2 Menampilkan feed video capture card sebagai sumber yang dapat ditambahkan ke scene.
- FR-1.3 Mendukung resolusi masukan 720p dan 1080p pada 30 fps; 60 fps sebagai opsi bila perangkat mampu.
- FR-1.4 Pengguna dapat memilih format masukan (MJPEG atau YUV) bila perangkat menawarkan lebih dari satu, dengan default otomatis yang paling hemat.
- FR-1.5 Menangani pencabutan kabel saat merekam: sumber ditandai terputus, rekaman tetap berjalan, dan sumber tersambung kembali otomatis saat dicolok lagi.
- FR-1.6 Menampilkan informasi sinyal (resolusi, fps aktual) pada panel info sumber.

### 7.2 Perekaman Layar (P0)

- FR-2.1 Merekam layar ponsel sebagai sumber video di dalam scene, dapat dikombinasikan dengan sumber lain.
- FR-2.2 Pilihan resolusi tangkapan layar (asli, 1080p, 720p) dan frame rate (30 atau 60 fps).
- FR-2.3 Opsi menyertakan audio internal ponsel bila didukung sistem, dengan penjelasan jelas bila tidak tersedia.
- FR-2.4 Kontrol melayang kecil (mulai, jeda, berhenti, ganti scene) yang tidak ikut terekam.
- FR-2.5 Mode lanskap dan potret dengan orientasi keluaran yang dapat dikunci.

### 7.3 Perekaman Audio (P0)

- FR-3.1 Sumber audio yang didukung: audio capture card, mic headset (kabel atau Bluetooth), mic internal ponsel, audio internal ponsel (bila didukung).
- FR-3.2 Setiap sumber audio memiliki fader volume, tombol mute, dan indikator level (meter).
- FR-3.3 Beberapa sumber audio dapat aktif bersamaan; hasil digabung (mix) ke satu track atau dipisah ke beberapa track audio dalam satu file (opsi, P1).
- FR-3.4 Pemilihan perangkat audio otomatis saat headset dicolok atau dicabut, dengan notifikasi.
- FR-3.5 Pengaturan dasar per mic: penguat (gain), noise suppression ringan, dan limiter untuk mencegah distorsi.
- FR-3.6 Sinkronisasi audio-video dengan toleransi maksimal 50 ms; tersedia penggeser offset per sumber audio (P1).

### 7.4 Preview dan Monitoring (P0)

- FR-4.1 Preview video real time yang menampilkan persis hasil komposisi scene aktif.
- FR-4.2 Monitoring audio melalui headphone dengan tiga mode per sumber: mati, hanya monitor, monitor dan rekam.
- FR-4.3 Peringatan otomatis bila monitoring lewat speaker berisiko umpan balik (feedback), dan saran memakai headphone.
- FR-4.4 Indikator latensi monitoring; pada Bluetooth ditampilkan peringatan bahwa suara dapat tertunda.
- FR-4.5 Preview dapat dimatikan sementara atau diturunkan kualitasnya untuk menghemat daya tanpa memengaruhi kualitas rekaman.

### 7.5 Kamera Lokal dan Eksternal (P0)

- FR-5.1 Menambahkan kamera depan atau belakang ponsel sebagai sumber, dapat diposisikan sebagai overlay (picture-in-picture) atau layar penuh.
- FR-5.2 Menambahkan kamera eksternal USB (webcam UVC) sebagai sumber.
- FR-5.3 Kontrol dasar kamera: fokus (otomatis, manual, kunci), eksposur, white balance, zoom, dan balik cermin.
- FR-5.4 Overlay dapat digeser, diubah ukuran, diberi bentuk (persegi, bulat, sudut membulat), dan bingkai tipis.

### 7.6 Mode Dua Ponsel (P0)

HP A adalah Perekam Utama (Host) yang menjalankan scene, mixer, dan perekaman. HP B adalah Kamera Fokus (Camera Node) yang hanya mengirim gambar kamera.

- FR-6.1 Pairing sederhana: HP A menampilkan kode QR, HP B memindai; keduanya harus berada di jaringan Wi-Fi yang sama atau memakai hotspot salah satu ponsel.
- FR-6.2 HP B menjalankan mode ringan yang hanya menampilkan pratinjau kecil, status koneksi, dan kontrol fokus lokal.
- FR-6.3 Aliran video dari HP B muncul di HP A sebagai sumber biasa yang dapat ditambahkan ke scene mana pun.
- FR-6.4 HP A dapat mengendalikan fokus, zoom, eksposur, dan lampu senter HP B dari jarak jauh.
- FR-6.5 Opsi mengirim audio mic HP B ke HP A (berguna bila HP B lebih dekat ke sumber suara).
- FR-6.6 Target latensi video antar ponsel di bawah 250 ms pada jaringan lokal yang sehat, dengan pilihan mode Kualitas atau Latensi Rendah.
- FR-6.7 Bila koneksi putus, HP A menampilkan frame pengganti (placeholder) dan menyambung ulang otomatis hingga 30 detik tanpa menghentikan rekaman.
- FR-6.8 Mendukung 2 sampai 3 Camera Node sekaligus pada 1.0. Dua node adalah target wajib, node ketiga bergantung pada hasil uji beban di perangkat acuan. Setiap node dibatasi 720p 30 fps secara default.
- FR-6.9 Teknologi transport disarankan WebRTC atau SRT di jaringan lokal, dengan enkripsi dan token pairing sekali pakai.

### 7.7 Operasi Bersamaan (P0)

- FR-7.1 Semua sumber (capture card, layar, kamera lokal, kamera eksternal, kamera HP B, dan seluruh sumber audio) dapat aktif secara bersamaan dalam satu rekaman, bukan memilih salah satu.
- FR-7.2 Satu file hasil dengan satu track video dan satu atau lebih track audio (format MP4, kodek H.264 default, HEVC opsional bila perangkat mendukung).
- FR-7.3 Sistem memberi estimasi beban sebelum rekaman dimulai (misalnya "Beban perkiraan: sedang") berdasarkan kombinasi sumber dan resolusi, serta menyarankan penyesuaian bila diperkirakan berat.
- FR-7.4 Bila perangkat tidak sanggup menjalankan seluruh kombinasi, aplikasi menurunkan kualitas secara bertahap sesuai urutan prioritas yang dapat diatur pengguna, bukan berhenti mendadak.

### 7.8 Scene dan Preset (P0)

- FR-8.1 Membuat, menggandakan, mengganti nama, mengurutkan, dan menghapus scene.
- FR-8.2 Setiap scene menyimpan: daftar sumber, posisi dan ukuran tiap sumber, urutan lapisan (layer), status mute dan level audio, serta preset kualitas.
- FR-8.3 Berpindah scene saat merekam dengan satu ketukan; transisi sederhana berupa potong langsung (cut) atau pudar (fade) selama 0,3 detik.
- FR-8.4 Template scene bawaan, misalnya: Layar Penuh, Kamera Penuh, Gameplay + Kamera Wajah, Layar + Kamera Sudut, Dua Kamera Berdampingan.
- FR-8.5 Preset kualitas bawaan: Hemat (720p 30 fps, bitrate rendah), Seimbang (1080p 30 fps), Tinggi (1080p 60 fps). Pengguna dapat membuat preset sendiri.
- FR-8.6 Pintasan perpindahan scene melalui tombol volume, tombol melayang, atau gestur geser pada pratinjau (dapat diaktifkan di pengaturan).
- FR-8.7 Ekspor dan impor konfigurasi proyek sebagai file untuk dipindahkan antar perangkat (P1).

### 7.9 Elemen Tambahan Minimal (P1)

- Overlay teks dan gambar statis (logo, watermark).
- Penghitung durasi rekaman dan sisa ruang penyimpanan pada layar utama.
- Penanda waktu (marker) saat merekam untuk memudahkan penyuntingan.

### 7.10 Live Streaming (P0 bersyarat)

Live streaming ke YouTube, Twitch, dan TikTok masuk 1.0 selama bisa dijalankan tanpa layanan berbayar. Jalurnya RTMP atau RTMPS langsung dari ponsel ke server platform.

- FR-10.1 Mengirim siaran lewat RTMP atau RTMPS langsung ke server ingest platform, tanpa server perantara dan tanpa biaya.
- FR-10.2 Template per platform (YouTube, Twitch, TikTok) berisi alamat server dan saran bitrate. Pengguna dapat menyimpan lebih dari satu profil akun per platform (misalnya dua akun TikTok), masing-masing dengan stream key sendiri, dan memilih profil yang dipakai sebelum mulai siaran.
- FR-10.3 Siaran dan rekaman lokal berjalan bersamaan dari satu hasil encode, sehingga beban tidak berlipat. Encoder kedua dengan bitrate berbeda hanya opsi P1 bila beban memungkinkan.
- FR-10.4 Batas kualitas siaran 1080p 30 fps; default 720p 30 fps pada perangkat acuan.
- FR-10.5 Bitrate adaptif: turun otomatis saat jaringan lambat dan naik kembali saat stabil.
- FR-10.6 Sambung ulang otomatis hingga 60 detik tanpa menghentikan rekaman lokal.
- FR-10.7 Panel siaran menampilkan status (menyiarkan, menyambung ulang), bitrate aktual, dropped frame, dan durasi.
- FR-10.8 Stream key disimpan terenkripsi di perangkat (Android Keystore) dan tidak dikirim ke pihak lain.
- FR-10.9 Siaran ke beberapa platform sekaligus tidak termasuk 1.0 karena beban uplink dan encoder.

Syarat akun di tiap platform berada di luar kendali aplikasi. TikTok hanya memberi stream key kepada akun yang memenuhi syarat dari TikTok, dan syarat itu dapat berubah. Satu akun TikTok Anda sudah memenuhi syarat dan satu lagi belum, sehingga pemilihan profil akun menjadi wajib, dan profil yang belum memenuhi syarat ditandai belum siap. YouTube dan Twitch tetap berjalan bila TikTok tidak bisa dipakai.

### 7.11 Chat Langsung (P1)

Panel Chat menampilkan pesan chat penonton secara real time hanya di layar pengguna. Chat tidak masuk ke scene, tidak terekam, dan tidak ikut tersiar. Fitur ini hanya aktif saat siaran langsung berjalan.

- FR-11.1 Panel Chat dapat dibuka sebagai panel di layar utama (potret) atau jendela melayang yang bisa digeser dan diubah ukurannya (lanskap), dan dapat disembunyikan dengan satu ketukan.
- FR-11.2 Setiap pesan menampilkan nama pengirim, isi pesan, dan penanda peran sederhana (moderator, pelanggan) berupa teks atau ikon garis. Isi pesan dari penonton ditampilkan apa adanya; aturan tanpa emoji berlaku untuk antarmuka aplikasi, bukan untuk pesan penonton.
- FR-11.3 Chat mengikuti profil akun siaran yang dipilih (FR-10.2). Profil menyimpan nama kanal atau pengenal siaran yang diperlukan untuk menyambung ke chat.
- FR-11.4 Pengguliran otomatis ke pesan terbaru; berhenti otomatis saat pengguna menggulir ke atas, dengan tombol untuk kembali ke pesan terbaru.
- FR-11.5 Ukuran teks dapat diatur. Jumlah pesan yang disimpan di memori dibatasi (misalnya 200 pesan terbaru).
- FR-11.6 Penyaring sederhana opsional untuk menyembunyikan pesan bot dan perintah.
- FR-11.7 Baca-saja: tidak ada fitur membalas, menghapus pesan, atau moderasi pada 1.0.
- FR-11.8 Status sambungan chat (tersambung, menyambung ulang, tidak tersedia) ditampilkan, dengan sambung ulang otomatis tanpa memengaruhi siaran atau rekaman.
- FR-11.9 Hemat beban: pembaruan tampilan dikelompokkan (maksimal beberapa kali per detik), panel berhenti memperbarui saat layar mati, dan panel tidak melewati compositor video.
- FR-11.10 Pesan chat tidak disimpan ke penyimpanan setelah siaran selesai.
- FR-11.11 Menampilkan chat di ponsel kedua (Camera Node) adalah P2 dan tidak termasuk 1.0.

Sumber data chat berbeda di tiap platform:

| Platform | Cara membaca chat | Catatan |
| --- | --- | --- |
| Twitch | Koneksi baca-saja anonim ke chat kanal | Tanpa login, paling mudah, dikerjakan pertama |
| YouTube | YouTube Live Streaming API (pesan live chat) | Butuh login Google atau kunci API dan ada batas kuota; menjadi pengecualian dari prinsip tanpa akun, khusus untuk fitur ini |
| TikTok | Belum ada jalur resmi yang diketahui untuk membaca chat dari sisi pembuat siaran | Tidak dijamin; pustaka tidak resmi rapuh dan berisiko melanggar ketentuan platform, jadi ditandai belum tersedia pada 1.0 dan dicek ulang saat pengembangan |

## 8. Desain Antarmuka

### 8.1 Prinsip

- Bersih dan lapang: satu tugas utama per layar, margin lega, hirarki yang jelas.
- Tema terang sebagai default; tema gelap tidak disediakan pada 1.0 sesuai arahan produk.
- Tanpa emoji di seluruh teks, label, notifikasi, dan materi bantuan. Ikon memakai set garis tipis yang konsisten.
- Tanpa warna neon; gunakan warna lembut dengan kontras teks yang memenuhi WCAG AA.
- Tombol rekam selalu mudah dijangkau satu tangan dan statusnya jelas tanpa bergantung pada warna saja (disertai label dan bentuk).

### 8.2 Palet Warna yang Disarankan

| Peran | Warna | Keterangan |
| --- | --- | --- |
| Latar | #F6F7F9 | Abu sangat terang |
| Permukaan/kartu | #FFFFFF | Putih |
| Teks utama | #1F2933 | Abu tua, bukan hitam pekat |
| Teks sekunder | #5B6670 | Abu sedang |
| Aksen utama | #3D6FA5 | Biru teduh |
| Status rekam | #C0504D | Merah bata lembut |
| Berhasil | #4F8A6B | Hijau teduh |
| Peringatan | #C98A2B | Kuning tua lembut |
| Garis pemisah | #E2E6EA | Abu terang |

### 8.3 Struktur Layar

1. Layar Utama: pratinjau besar di atas, deretan scene sebagai kartu geser di bawahnya, tombol rekam di tengah bawah, indikator suhu dan baterai kecil di sudut.
2. Panel Sumber: daftar sumber pada scene aktif dengan ikon status (aktif, terputus), tombol tambah sumber.
3. Panel Mixer: fader dan meter untuk tiap sumber audio, pilihan monitoring.
4. Editor Scene: kanvas dengan penggeseran dan perubahan ukuran sumber memakai gestur, daftar layer, alat penyelarasan (snap) ke tepi dan tengah.
5. Pengaturan: kualitas, penyimpanan, perangkat audio, mode hemat daya, bantuan.
6. Layar Dua Ponsel: pairing, status koneksi, kontrol jarak jauh untuk 2 sampai 3 Camera Node.
7. Panel Siaran: pilihan platform, stream key, status siaran, bitrate, dan dropped frame.
8. Panel Chat: pesan chat penonton secara real time, hanya terlihat oleh pengguna dan tidak ikut terekam atau tersiar.

### 8.4 Mode Potret dan Lanskap

Antarmuka berpindah tata letak sesuai orientasi: potret untuk pengendalian satu tangan, lanskap untuk pratinjau lebih besar dengan panel dapat disembunyikan.

## 9. Kebutuhan Non-Fungsional

### 9.1 Kinerja dan Efisiensi (Prioritas Tinggi)

| Sasaran | Target |
| --- | --- |
| Penggunaan CPU rata-rata saat merekam 1080p 30 fps dengan 3 sumber | di bawah 40 persen pada perangkat acuan, profil Standar |
| Frame drop | di bawah 1 persen dalam sesi 60 menit |
| Peningkatan suhu | tidak mencapai status termal berat (severe) dalam 60 menit pada suhu ruang normal |
| Konsumsi baterai | di bawah 20 persen per jam pada mode Seimbang (tanpa pengisian daya) |
| Waktu mulai aplikasi hingga siap merekam | di bawah 3 detik |
| Sinkronisasi A/V | selisih di bawah 50 ms |

Profil beban pada perangkat acuan (target awal, divalidasi lewat prototipe):

| Profil | Kombinasi sumber | Keluaran | Status |
| --- | --- | --- | --- |
| Ringan | Satu sumber video dan satu mic | 1080p 30 fps | Wajib stabil |
| Standar | Capture card, kamera lokal sebagai overlay, mic, audio capture card, boleh dengan siaran langsung | Rekam 1080p 30 fps, siaran 720p 30 fps | Wajib stabil |
| Berat | Capture card, rekam layar, dua Camera Node, semua audio | 720p 30 fps | Target, dengan peringatan beban |
| Maksimum | Capture card, tiga Camera Node, kamera lokal | 720p 30 fps, tanpa siaran bersamaan | Opsional, hasil uji yang menentukan |

Penggunaan memori puncak ditargetkan di bawah 1,2 GB pada RAM 4 GB. Aplikasi menampilkan estimasi beban sebelum rekaman, memperingatkan bila kombinasi melampaui profil Maksimum, dan menawarkan penurunan kualitas otomatis.

### 9.2 Strategi Teknis untuk Menjaga Ringan

1. Encoder perangkat keras: gunakan hardware encoder untuk seluruh kompresi; hindari encoding perangkat lunak.
2. Compositing di GPU: penggabungan sumber video dengan OpenGL ES atau Vulkan, tanpa menyalin frame ke CPU. Alur zero-copy dari surface sumber ke surface encoder.
3. Satu encoder untuk semua: komposisi dilakukan sekali, dikodekan sekali, bukan per sumber.
4. Sumber tidak terlihat dihentikan: sumber yang tidak muncul pada scene aktif tidak dirender, namun tetap siaga (warm standby) bila diperlukan agar perpindahan scene cepat.
5. Pratinjau hemat: pratinjau berjalan pada resolusi dan fps lebih rendah dari rekaman (misalnya 540p 15-24 fps), dapat dimatikan penuh.
6. Manajemen termal adaptif: memantau Thermal API Android dan menerapkan tahapan otomatis:
   - Ringan: turunkan fps pratinjau.
   - Sedang: turunkan resolusi pratinjau dan kecerahan layar yang disarankan.
   - Berat: turunkan bitrate dan fps rekaman, tampilkan peringatan.
   - Kritis: simpan file dengan aman dan hentikan rekaman dengan pesan jelas.
7. Mode Hemat Daya: preset khusus yang mematikan pratinjau, membatasi 720p 30 fps, dan menonaktifkan efek.
8. Audio ringan: pemrosesan audio pada thread prioritas rendah latensi, noise suppression ringan, tanpa efek berat.
9. Penulisan file bertahap: tulis segmen secara berkala agar file tetap bisa dipulihkan bila aplikasi berhenti mendadak.
10. Batas sumber yang realistis: peringatan atau pembatasan bila kombinasi melebihi kemampuan perangkat (misalnya dua kamera 1080p + capture card 1080p60 + layar 1080p60).

### 9.3 Keandalan

- Pemulihan file rekaman setelah crash atau baterai habis (segmentasi MP4 fragmented).
- Peringatan penyimpanan hampir penuh dan penghentian aman sebelum ruang habis.
- Layanan latar depan (foreground service) agar rekaman tidak dimatikan sistem, dengan notifikasi status.

### 9.4 Privasi dan Keamanan

- Semua pemrosesan dilakukan di perangkat; tidak ada unggahan otomatis.
- Transport antar ponsel dienkripsi dan hanya menerima perangkat yang dipasangkan.
- Izin (kamera, mic, rekam layar, penyimpanan) diminta kontekstual dengan penjelasan singkat.
- Indikator jelas ketika kamera, mic, atau layar sedang direkam.

### 9.5 Aksesibilitas dan Bahasa

- Bahasa Indonesia dan Inggris pada 1.0.
- Ukuran sentuh minimal 48 dp, dukungan ukuran font sistem, label untuk pembaca layar.

### 9.6 Komponen Gratis dan Sumber Terbuka

Seluruh komponen dipilih dari API bawaan Android atau pustaka berlisensi bebas, sehingga tidak ada biaya, watermark, iklan, atau akun. Lisensi tiap pustaka diperiksa ulang sebelum diintegrasikan.

| Fungsi | Pilihan | Lisensi (perkiraan, perlu diverifikasi) |
| --- | --- | --- |
| Antarmuka | Jetpack Compose | Apache 2.0 |
| Kamera lokal | CameraX atau Camera2 | Apache 2.0 |
| Capture card dan webcam USB | Pustaka UVC berbasis libuvc, misalnya UVCAndroid | Apache 2.0 dan BSD |
| Rekam layar | MediaProjection | Bawaan Android |
| Encoder dan muxer | MediaCodec dan MediaMuxer | Bawaan Android |
| Penggabungan video | OpenGL ES | Bawaan Android |
| Audio | AudioRecord dan Oboe | Apache 2.0 |
| Antar ponsel | WebRTC atau SRT | BSD dan MPL 2.0 |
| RTMP | RootEncoder atau implementasi sendiri | Apache 2.0 |
| Chat Twitch | Koneksi WebSocket baca-saja anonim ke chat Twitch | Layanan gratis, tanpa login |
| Chat YouTube | YouTube Live Streaming API (pesan live chat) | Gratis dengan batas kuota, perlu login Google atau kunci API |

Dihindari: SDK berbayar, pustaka iklan atau analitik pihak ketiga, dan build FFmpeg berlisensi GPL.

## 10. Alur Pengguna Utama

### 10.1 Rekam Gameplay dengan Capture Card, Mic, dan Kamera Wajah

1. Pengguna mencolok capture card; aplikasi menampilkan sumber terdeteksi.
2. Pengguna memilih template Gameplay + Kamera Wajah.
3. Aplikasi menambahkan capture card sebagai layar utama dan kamera depan sebagai overlay.
4. Di mixer, pengguna mengaktifkan audio capture card dan mic headset, lalu menyalakan monitoring.
5. Pengguna menyesuaikan posisi overlay dan menekan Rekam.
6. Selama rekaman, pengguna berpindah ke scene Kamera Penuh untuk reaksi, lalu kembali.
7. Pengguna menekan Berhenti; file tersimpan dan notifikasi menunjukkan lokasi serta durasi.

### 10.2 Mode Dua Ponsel

1. Di HP A, pengguna memilih Tambah Sumber, lalu Kamera Ponsel Lain, dan QR ditampilkan.
2. Di HP B, pengguna membuka mode Kamera Fokus dan memindai QR.
3. Gambar HP B muncul pada HP A; pengguna mengatur fokus dan zoom dari HP A.
4. Pengguna menaruh sumber tersebut ke scene Fokus Produk, lalu merekam dengan HP A.

## 11. Metrik Keberhasilan

| Metrik | Target pada perangkat acuan |
| --- | --- |
| Sesi rekam berdurasi lebih dari 30 menit tanpa kegagalan | 95 persen |
| Sesi profil Standar yang mencapai status termal berat | di bawah 10 persen |
| Capture card milik sendiri terdeteksi saat dicolok | 100 persen |
| Pairing dua ponsel berhasil pada percobaan pertama | 85 persen |
| Siaran langsung 60 menit tanpa terputus (di luar gangguan jaringan) | 90 persen |
| Waktu dari membuka aplikasi sampai mulai merekam dengan template | di bawah 30 detik |

## 12. Roadmap

| Fase | Cakupan |
| --- | --- |
| MVP (Android) | Capture card, rekam layar, kamera lokal, mic, mixer, preview, scene dan preset, perekaman bersamaan, manajemen termal dasar |
| 1.0 | Mode dua ponsel dengan 2 sampai 3 Camera Node, kamera eksternal USB, multi-track audio, pemulihan file, daftar perangkat kompatibel, live streaming RTMP ke YouTube, Twitch, dan TikTok (bersyarat, bila stabil di perangkat acuan), serta Panel Chat untuk Twitch dan YouTube |
| 1.1 | Overlay teks dan gambar, penanda waktu, ekspor dan impor proyek |
| Ditunda | iOS, resolusi di atas 1080p, siaran multi-platform, layanan streaming berbayar |

## 13. Risiko dan Mitigasi

| Risiko | Dampak | Mitigasi |
| --- | --- | --- |
| Variasi dukungan UVC dan audio USB antar merek ponsel | Capture card tidak terdeteksi | Daftar kompatibilitas, uji perangkat nyata, mode diagnostik, laporan masalah |
| Ponsel panas saat banyak sumber aktif | Rekaman turun kualitas atau berhenti | Manajemen termal adaptif, estimasi beban, preset hemat, saran pendingin eksternal |
| Pembatasan audio internal di Android dan ReplayKit di iOS | Fitur tidak lengkap | Komunikasi jelas di aplikasi, jalur alternatif lewat audio capture card |
| Latensi dan ketidakstabilan Wi-Fi pada mode dua ponsel | Gambar kamera tersendat | Mode Latensi Rendah, adaptasi bitrate, saran memakai 5 GHz atau hotspot langsung |
| Latensi Bluetooth pada headset | Suara monitoring dan rekaman tidak sinkron | Peringatan dan offset audio manual, rekomendasi headset kabel |
| Proses latar belakang dimatikan sistem | Rekaman terhenti | Foreground service, panduan pengaturan baterai per merek |
| Perangkat acuan berspek rendah (Helio G99, RAM 4 GB) menjalankan banyak sumber | Frame drop, panas, atau aplikasi dimatikan sistem karena memori habis | Profil beban bertingkat, default 720p 30 fps pada kombinasi berat, anggaran memori 1,2 GB |
| Syarat akun dan kebijakan platform siaran berubah, terutama TikTok | Siaran ke platform tertentu tidak bisa dipakai | Template per platform yang mudah diubah, YouTube dan Twitch sebagai jalur utama |
| Sumber data chat tidak stabil atau tidak tersedia (terutama TikTok), dan kuota API YouTube terbatas | Panel Chat kosong atau terputus pada platform tertentu | Twitch dikerjakan dulu, lalu YouTube, TikTok ditandai belum dijamin; status sambungan chat selalu terlihat |

## 14. Keputusan Produk dan Pertanyaan Tersisa

| Pertanyaan | Keputusan | Dampak pada PRD |
| --- | --- | --- |
| Android atau iOS | Android saja dulu | Bagian 5 dan roadmap |
| Model bisnis | Gratis, untuk pemakaian pribadi, semua komponen gratis, tanpa watermark | Bagian 1, 3, dan 9.6 |
| Resolusi maksimal | 1080p cukup | 4K masuk Non-Tujuan |
| Live streaming di 1.0 | Ya untuk YouTube, TikTok, dan Twitch bila bisa tanpa layanan berbayar; bila tidak, boleh ditunda | Bagian 7.10 dan roadmap |
| Jumlah Camera Node | 2 sampai 3 | FR-6.8 |
| Perangkat acuan | Android 12, Exynos 9825, Helio G99, RAM 4 GB | Bagian 5 dan 9.1 |
| Fitur chat langsung | Dilihat sendiri oleh pengguna, tidak ikut terekam atau tersiar | Bagian 7.11 dan 8.3 |

Jawaban atas pertanyaan tambahan:

- Akun TikTok: satu akun sudah memenuhi syarat dan satu belum, jadi pengguna memilih akun yang dipakai untuk siaran (lihat FR-10.2).
- Perangkat acuan Exynos 9825 dan Helio G99 tersedia untuk uji prototipe.
- Aplikasi dipasang langsung sebagai file APK di perangkat sendiri, tanpa toko aplikasi.

&#32;Ya sudah dan belum, jadi bisa dipilih ingin stream dengan akun mana

&#32;Tersedia

&#32;Ya benar

## 15. Lampiran: Catatan Asumsi

- Poin nomor 6 pada daftar kebutuhan awal tidak ada, sehingga penomoran dilanjutkan sesuai isi yang diberikan.
- Butir tentang dua ponsel ditafsirkan sebagai: satu ponsel sebagai perekam utama dan ponsel lain sebagai sumber kamera yang fokusnya dapat diatur.
- Angka target performa adalah titik awal dan perlu divalidasi lewat prototipe pada perangkat nyata.
- Aplikasi dipasang langsung sebagai APK tanpa toko aplikasi (sudah dikonfirmasi), sehingga persyaratan toko dan monetisasi tidak dibahas.
- Target performa dibuat bertingkat karena Helio G99 dan RAM 4 GB adalah batas bawah perangkat acuan.
- Daftar lisensi pustaka pada bagian 9.6 adalah perkiraan awal dan harus diverifikasi sebelum integrasi.
- Chat langsung hanya dilihat sendiri oleh pengguna (sudah dikonfirmasi). Ketersediaan sumber chat tiap platform, terutama TikTok, perlu dicek ulang saat pengembangan karena dapat berubah.
