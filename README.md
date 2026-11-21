# Lebah Madu

Struktur repo (semuanya di root repo GitHub Anda):

- `config.json`  : kontrol login (kode & saklar on/off) oleh administrator
- `index.html`   : halaman utama yang tampil di app (GitHub Pages)
- `app/`, `build.gradle`, ... : proyek Android
- `.github/workflows/build-apk.yml` : build APK otomatis

## Cara pakai
1. Upload semua isi folder ini ke repo GitHub (branch `main`).
2. Settings > Pages > Deploy from a branch > `main` / `(root)`.
3. Tab Actions > "Build APK" > Run workflow. Unduh APK dari bagian Artifacts.
   (URL sudah diatur ke github.com/8xploit/LebahMadu di AppConfig.kt.)

## Administrator
Edit `config.json` langsung di GitHub:
- `"login_enabled": false`  -> login dinonaktifkan
- ubah/hapus isi `"codes"`  -> kode lama tidak berlaku (sesi lama berakhir saat app dibuka & online)
Edit `index.html` untuk mengubah halaman utama. Tidak perlu build ulang APK.

## Masa aktif kode (durasi)
Tulis tanggal dengan format **DD/MM/YYYY**, contoh `31/12/2099`. Zona waktu WIB.

```json
{
  "login_enabled": true,
  "codes": [
    { "code": "n3m0",  "mulai": "21/11/2026", "bulan": 1 },
    { "code": "yon",   "expired": "31/12/2099" },
    "hardi"
  ]
}
```

- `"mulai"` + `"bulan"` -> aktif 30 hari x jumlah bulan, dihitung dari tanggal `mulai`
- `"expired"`           -> aktif sampai akhir tanggal itu
- kode tanpa tanggal    -> aktif selamanya (format lama tetap jalan)
- Format lama `YYYY-MM-DD` masih diterima, jadi config lama tidak rusak.

Perpanjang masa aktif cukup dengan mengubah tanggal/angka bulan di `config.json`
lalu simpan ke GitHub. Tidak perlu membuat APK baru.

Uji aturan tanggal (butuh Kotlin di komputer Anda, tidak dipakai saat build APK):

```
kotlinc app/src/main/java/com/lebahmadu/app/ExpiryDate.kt tools/ExpiryDateTest.kt -include-runtime -d /tmp/date-test.jar
java -jar /tmp/date-test.jar
```
