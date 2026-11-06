# Playlist Audio (Android)

App Android: dán link playlist YouTube → tải audio → **cắt đầu / cắt cuối** → **tăng tốc** → **gộp mỗi N video thành 1 file mp3**. Mọi thứ chạy trên điện thoại (dùng mạng của máy nên không bị YouTube chặn như máy chủ).

## Tải APK
Vào **[Releases](../../releases/latest)** → tải `PlaylistAudio-arm64-v8a-<phiên bản>.apk` (hầu hết điện thoại) → mở để cài (cho phép "Cài ứng dụng không rõ nguồn gốc").

Mỗi lần push code, GitHub Actions tự build và tạo Release mới. Có thể cài đè bản mới lên bản cũ.

## Dùng
| Ô | Ý nghĩa |
|---|---|
| Link | link playlist hoặc video (có thể bấm *Chia sẻ* trong app YouTube → chọn Playlist Audio) |
| Từ video số / Số video | vd 1 và 10 = 10 video đầu; số video = 0 → tới hết playlist |
| Cắt đầu / Cắt cuối | giây (`10`) hoặc phút:giây (`1:05`) |
| Tốc độ | `1.25`, `1.5`, `2`… |
| Gộp mỗi N video | mặc định 10 |

Kết quả: `Download/PlaylistAudio/<tên playlist>_01_v1-10.mp3`, … kèm **ảnh bìa** (thumbnail video đầu nhóm, hoặc ảnh tự chọn trong máy: bấm ô ảnh cạnh "Mono") và tag album/tên/số phần để app nghe nhạc nhóm đúng.

App nghe gợi ý (nhẹ, không quảng cáo, nhớ vị trí đang nghe): **Voice** (F-Droid), **Musicolet** (Play Store).

- Tối ưu tốc độ: tải song song 4 video (hiện tốc độ MB/s) → chia mỗi video thành nhiều đoạn, mã hoá MP3 song song trên **tất cả nhân CPU** → ghép bằng copy (không mã hoá lại). Nhật ký ghi thời gian từng giai đoạn.
- Chạy nền có thông báo, tắt màn hình vẫn chạy; bấm *Dừng* trên thông báo để huỷ.
- Bị ngắt giữa chừng → bấm Bắt đầu lại với cùng link, các video đã tải được giữ lại, không tải lại (đổi cắt/tốc độ cũng không phải tải lại).
- Mỗi lần chạy app tự cập nhật yt-dlp mới nhất.

## Kỹ thuật
- [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1: đóng gói Python + yt-dlp + ffmpeg + QuickJS (JS runtime yt-dlp cần cho YouTube từ 2025.11).
- Xử lý: `ffmpeg -ss <đầu> -t <dài - đầu - cuối> -af atempo=<tốc độ> -c:a libmp3lame`, gộp bằng concat demuxer (`-c copy`).
- `app/release.keystore` là khoá ký công khai chỉ để các bản build cài đè được lên nhau — **không dùng để phát hành lên Play Store**.
