# TrashLens — Klasifikasi Gambar Sampah

Aplikasi Android native khusus untuk klasifikasi gambar sampah menggunakan kamera belakang perangkat dan model EfficientNetB0 yang dijalankan secara lokal melalui TensorFlow Lite.

## Fitur

- **Scan Sampah** — pratinjau kamera langsung, ambil foto, lalu klasifikasikan satu objek.
- **Riwayat** — hasil scan, foto, waktu, dan confidence disimpan secara lokal di perangkat. Maksimal 50 hasil terbaru.
- **Tentang Aplikasi** — informasi model, enam kelas, metrik evaluasi, dan batasan model.
- **Offline** — foto tidak dikirim ke server. Aplikasi tidak meminta izin internet.

## Kategori

`cardboard`, `glass`, `metal`, `paper`, `plastic`, `trash`.

## Model Machine Learning

- Arsitektur: EfficientNetB0.
- Format Android: TensorFlow Lite (`trashnet_model_final.tflite`).
- Input: RGB 224 × 224, `float32`, nilai piksel 0–255; model export mengandung rescaling yang digunakan saat training.
- Test accuracy yang dilaporkan: **86,98% (334 dari 384 gambar)**.
- Validation accuracy terbaik yang tercatat: 90,19%.

Confidence adalah skor keluaran model, bukan kepastian prediksi. Pada evaluasi test, kelas `trash`, `glass`, dan `plastic` lebih sulit dikenali. Periksa kembali sebelum memilah sampah, khususnya untuk gambar campuran atau kondisi pencahayaan buruk.

## Menjalankan di Android Studio

1. Ekstrak ZIP proyek.
2. Buka folder `android/` di Android Studio.
3. Gunakan JDK yang kompatibel dengan Gradle wrapper dan Android Gradle Plugin pada proyek.
4. Tunggu Gradle Sync selesai (internet diperlukan pertama kali untuk mengunduh dependency).
5. Jalankan aplikasi pada perangkat/emulator dengan kamera.
6. Izinkan akses kamera ketika diminta.
7. Buka **Scan Sampah**, arahkan kamera ke satu objek, lalu tekan **Ambil Foto & Klasifikasikan**.

## Struktur utama

```text
android/
├── app/
│   └── src/main/
│       ├── assets/trashnet_model_final.tflite
│       ├── java/com/trio/customerreview/
│       │   ├── MainActivity.kt
│       │   └── TfliteImageClassifier.kt
│       ├── res/layout/activity_main.xml
│       └── AndroidManifest.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

## Privasi

Aplikasi hanya meminta permission kamera. Foto hasil scan dan catatan riwayat disimpan di direktori internal aplikasi. Tidak ada akses galeri atau upload server.

## Catatan Pengembangan

- Kamera menggunakan CameraX 1.6.2.
- Inferensi menggunakan TensorFlow Lite 2.17.0.
- Model TFLite dimuat dari `assets` tanpa kompresi dan dibaca secara lokal.
- Build APK harus diuji lewat Gradle Sync/build pada Android Studio karena kompilasi penuh memerlukan Android SDK dan dependency yang sesuai.
