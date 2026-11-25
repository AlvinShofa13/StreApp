# Lisensi Pustaka

Proyek StreApp hanya memakai komponen gratis atau sumber terbuka dengan lisensi permisif.
Tidak ada pustaka berbayar, iklan, analitik pihak ketiga, atau SDK tertutup.

## Lisensi Proyek

Kode sumber StreApp dirilis di bawah lisensi **Apache License 2.0** (lihat berkas [`LICENSE`](../LICENSE) di root repositori).

## Daftar Pustaka Pihak Ketiga

| Pustaka | Versi | Lisensi | Dipakai di |
| --- | --- | --- | --- |
| Kotlin stdlib | 2.2.10 | Apache-2.0 | semua modul |
| kotlinx.serialization | 1.6.3 | Apache-2.0 | `core-model`, `app` (JSON proyek) |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | `app` (engine & UI asinkron) |
| Jetpack Compose BOM (UI, Graphics, Material 3, Material Icons Extended) | 2024.09.03 | Apache-2.0 | `app` (antarmuka tema terang) |
| AndroidX Activity Compose | 1.9.2 | Apache-2.0 | `app` |
| AndroidX Lifecycle (Runtime, ViewModel, Service) | 2.8.6 | Apache-2.0 | `app` |
| AndroidX Navigation Compose | 2.8.3 | Apache-2.0 | `app` |
| OkHttp | 4.12.0 | Apache-2.0 | `app`, `chat` (WebSocket Twitch & API YouTube) |
| ZXing Core | 3.5.3 | Apache-2.0 | `app` (pembuatan QR pairing Camera Node) |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | `app` (pemindai QR Camera Node) |
| UVCAndroid (`com.herohan:UVCAndroid`) | 1.0.13 | Apache-2.0 | `source-uvc` (capture card & webcam USB) |
| hutool-core (bawaan UVCAndroid) | 5.8.35 | Mulan PSL v2 | `source-uvc` (tidak dipakai langsung) |
| libusb (native, bawaan UVCAndroid) | 1.0.x | LGPL-2.1 | `source-uvc` |
| libuvc (native, bawaan UVCAndroid) | 0.0.x | BSD-2-Clause | `source-uvc` |
| libjpeg-turbo (native, bawaan UVCAndroid) | 2.1.2 | BSD-3-Clause / IJG | `source-uvc` |
| libyuv (native, bawaan UVCAndroid) | - | BSD-3-Clause | `source-uvc` |
| AndroidX Core, AppCompat, Legacy Support, Annotation | bervariasi | Apache-2.0 | `source-uvc`, `app` |
| JUnit 4 | 4.13.2 | EPL-1.0 (hanya test) | unit test |
| Android Gradle Plugin | 9.3.3 | Apache-2.0 (hanya build) | build |
| Gradle | 9.5.0 | Apache-2.0 (hanya build) | build |
