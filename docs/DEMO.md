# Skenario Demo EcoScan (3–5 menit)

## Tujuan

Menunjukkan alur utama aplikasi: melihat beranda, mengklasifikasikan benda sampah dari kamera dan galeri, memahami hasil beserta confidence, serta melihat riwayat yang tersimpan lokal.

## Persiapan sebelum demo

- Jalankan aplikasi pada perangkat/emulator Android API 24 atau lebih baru.
- Pastikan aplikasi berhasil dibuka dan model TFLite sudah tersedia di `assets`.
- Siapkan dua foto/benda uji yang mudah dikenali, misalnya botol plastik dan kardus/kertas. Gunakan satu objek per foto, pencahayaan baik, dan latar yang tidak terlalu ramai.
- Jika memakai kamera, pastikan permission kamera sudah diberikan.
- Opsional: siapkan jaringan mati/airplane mode untuk menunjukkan inferensi berlangsung lokal. Jangan lakukan langkah ini sebelum model dan aplikasi sudah terpasang.

## Alur yang disarankan

| Waktu | Langkah | Yang ditunjukkan / narasi |
|---|---|---|
| 0:00–0:30 | Buka Beranda | Perkenalkan EcoScan, enam kategori, ringkasan scan, dan tips singkat. |
| 0:30–1:00 | Buka Scan dan kamera | Jelaskan aplikasi meminta izin kamera hanya untuk mengambil foto. Arahkan kamera ke satu benda sampah. |
| 1:00–2:00 | Ambil foto dan tunggu loading | Tekan tombol scan. Tunjukkan spinner/pesan loading, lalu label kelas, confidence, dan alternatif prediksi ketika hasil selesai. |
| 2:00–3:00 | Uji gambar dari galeri | Pilih foto benda sampah kedua dari galeri. Tunjukkan bahwa jalur galeri juga menampilkan loading dan hasil klasifikasi. |
| 3:00–3:45 | Buka Riwayat | Tunjukkan hasil scan tercatat otomatis dan tetap tersedia setelah pindah tab. Jelaskan bahwa riwayat dikelola di perangkat. |
| 3:45–4:30 | Buka Tentang | Jelaskan model EfficientNetB0 + TensorFlow Lite, enam kelas, dan batasan confidence. |
| 4:30–5:00 | Penutup | Tegaskan pemrosesan model dilakukan on-device dan hasil harus diperiksa secara visual. |

## Narasi singkat yang bisa dibacakan

> “EcoScan membantu mengenali kategori sampah dari satu foto. Pengguna bisa mengambil foto memakai kamera atau memilih gambar dari galeri. Aplikasi menampilkan indikator saat analisis berlangsung, lalu menyajikan kategori dan confidence. Model EfficientNetB0 berjalan melalui TensorFlow Lite di perangkat; aplikasi tidak mengirim foto ke server. Hasil tetap dapat keliru, terutama untuk objek campuran, latar ramai, atau cahaya buruk, jadi confidence bukan jaminan dan hasilnya perlu diperiksa.”

## Checklist sebelum selesai

- [ ] Beranda dan navigasi tampil normal.
- [ ] Kamera berhasil meminta izin dan preview aktif.
- [ ] Spinner tampil ketika foto kamera dianalisis dan kemudian hilang.
- [ ] Hasil kamera menampilkan kategori dan confidence.
- [ ] Pemilihan foto galeri berhasil, spinner tampil, dan hasil keluar.
- [ ] Hasil tersimpan di Riwayat.
- [ ] Halaman Tentang bisa dibuka.

## Rencana cadangan

- **Permission kamera ditolak:** buka pengaturan izin aplikasi atau lanjutkan demo melalui galeri.
- **Prediksi salah/confidence rendah:** jelaskan bahwa ini batas model, lalu ulangi dengan satu objek nyata yang memenuhi frame dan pencahayaan lebih baik. Jangan menyatakan model selalu benar.
- **Kamera emulator tidak tersedia:** gunakan pemilihan foto dari galeri.
- **Tidak ada internet:** inferensi tetap bisa dicoba karena model tersimpan di aplikasi; tetapi pemasangan/build pertama mungkin perlu internet untuk mengunduh dependency.
