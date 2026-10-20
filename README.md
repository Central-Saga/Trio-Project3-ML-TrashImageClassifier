# TrashLens — Klasifikasi Gambar Sampah

Aplikasi Android native untuk klasifikasi gambar sampah menggunakan kamera perangkat dan model EfficientNetB0 yang dijalankan secara lokal melalui TensorFlow Lite.

## Fitur

- **Scan Sampah** — pratinjau kamera langsung, ambil foto, lalu klasifikasikan satu objek.
- **Riwayat** — hasil scan, foto, waktu, dan confidence disimpan secara lokal di perangkat. Maksimal 50 hasil terbaru.
- **Tentang Aplikasi** — informasi model, enam kelas, metrik evaluasi, dan batasan model.
- **Offline** — foto tidak dikirim ke server. Aplikasi tidak meminta izin internet.

## Kategori

Model mengklasifikasikan gambar ke dalam enam kategori:

`cardboard`, `glass`, `metal`, `paper`, `plastic`, `trash`.

## Model Machine Learning

- **Arsitektur:** EfficientNetB0.
- **Format Android:** TensorFlow Lite (`trashnet_model_final.tflite`).
- **Lokasi model:** `android/app/src/main/assets/trashnet_model_final.tflite`.
- **Ukuran file:** sekitar 16,09 MB (16.087.840 byte).
- **Input:** RGB 224 × 224, `float32`, dengan nilai piksel 0–255. Model hasil ekspor mengandung rescaling yang digunakan saat training.
- **Output:** skor prediksi untuk enam kategori sampah.
- **Test accuracy yang dilaporkan:** **86,98% (334 dari 384 gambar)**.
- **Validation accuracy terbaik yang tercatat:** 90,19%.
- **Versi model:** tidak dicantumkan secara terpisah pada file model. Identitas file dapat diperiksa menggunakan SHA-256 berikut.

```text
CF419FD9F21265ABCB6B985499BAE05865E538288802A25814B47600F62A4C62
```

Model dimuat dari direktori `assets` dan digunakan untuk melakukan inferensi pada perangkat. Hasil prediksi digunakan oleh fitur Scan Sampah untuk menampilkan kategori dan confidence, bukan sekadar dicetak ke log.

Confidence merupakan skor keluaran model, bukan jaminan bahwa prediksi benar. Pada evaluasi test yang dilaporkan, kelas `trash`, `glass`, dan `plastic` lebih sulit dikenali. Periksa kembali hasil prediksi sebelum memilah sampah, khususnya untuk gambar campuran atau kondisi pencahayaan buruk.

## Menjalankan di Android Studio

1. Ekstrak ZIP proyek atau clone repository.
2. Buka folder `android/` di Android Studio.
3. Gunakan JDK yang kompatibel dengan Gradle Wrapper dan Android Gradle Plugin pada proyek.
4. Tunggu Gradle Sync selesai. Koneksi internet diperlukan pertama kali untuk mengunduh dependency.
5. Jalankan aplikasi pada perangkat atau emulator yang mendukung kamera.
6. Izinkan akses kamera ketika diminta.
7. Buka **Scan Sampah**, arahkan kamera ke satu objek, lalu tekan **Ambil Foto & Klasifikasikan**.
8. Periksa kategori dan confidence yang ditampilkan setelah inferensi selesai.

## Struktur Utama

```text
android/
├── app/
│   └── src/main/
│       ├── assets/
│       │   └── trashnet_model_final.tflite
│       ├── java/com/trio/trashlens/
│       │   ├── MainActivity.kt
│       │   └── TfliteImageClassifier.kt
│       ├── res/layout/activity_main.xml
│       └── AndroidManifest.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## Privasi

Aplikasi menggunakan permission kamera untuk melakukan pemindaian. Foto hasil scan dan catatan riwayat disimpan di direktori internal aplikasi. Foto tidak diunggah ke server, dan aplikasi tidak meminta izin internet berdasarkan konfigurasi proyek yang didokumentasikan.

## Catatan Pengembangan

- Kamera menggunakan CameraX 1.6.2.
- Inferensi menggunakan TensorFlow Lite 2.17.0.
- Model TFLite dimuat dari `assets` tanpa kompresi agar dapat dibaca menggunakan mekanisme pemuatan model yang digunakan aplikasi.
- Untuk memverifikasi perubahan kode, jalankan build Gradle dari folder `android/`.

### Verifikasi Build

Jalankan perintah berikut melalui PowerShell dari direktori `android/`:

```powershell
.\gradlew.bat assembleDebug
```

Build dinyatakan berhasil jika Gradle menampilkan `BUILD SUCCESSFUL`.


