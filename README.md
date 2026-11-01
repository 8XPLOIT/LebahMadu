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
