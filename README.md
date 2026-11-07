# Playlist Audio (Android)

App Android: dán link playlist YouTube → tải audio → **cắt đầu / cắt cuối** → **tăng tốc** → **gộp mỗi N video thành 1 file mp3**. Mọi thứ chạy trên điện thoại (dùng mạng của máy nên không bị YouTube chặn như máy chủ).

## Tải APK
Vào **[Releases](../../releases/latest)** → tải `PlaylistAudio-<phiên bản>.apk` (hầu hết điện thoại) → mở để cài (cho phép "Cài ứng dụng không rõ nguồn gốc").

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

## Bị YouTube chặn ("Sign in to confirm you're not a bot")
YouTube chặn theo IP khi bị hỏi quá dồn dập. App lấy thông tin lần lượt từng video (chờ ngẫu nhiên 1–3 s, giống hàng đợi của [YTDLnis](https://github.com/deniscerri/ytdlnis)) và dừng ngay khi bị chặn. Nếu vẫn bị: đổi mạng (wifi ↔ 4G) hoặc chờ vài giờ.

## Kiến trúc 1.x: khung + cập nhật trực tiếp từ GitHub
- **APK (khung, 1.0.0)**: WebView + cầu nối (`window.App`: chọn file/ảnh/thư mục, chạy engine, lưu file, lưu cài đặt), dịch vụ chạy nền, tự cập nhật APK. Hầu như không cần đổi.
- **Giao diện** [`web/index.html`](web/index.html): tải từ nhánh `main` mỗi lần mở app (hiện ngay bản đã lưu, có bản mới thì nạp lại).
- **Chức năng** [`engine/pipeline.py`](engine/pipeline.py): tải từ nhánh `main` mỗi lần bấm chạy, chạy bằng Python có sẵn trong app.
- Mất mạng: dùng bản đã lưu, cuối cùng là bản đóng gói trong APK.
- Sửa `web/` hoặc `engine/` rồi push lên `main` là xong, GitHub Actions không build APK. Chỉ khi đổi Kotlin mới tăng `appVersion` trong `gradle.properties` → Actions build và tạo release `vX.Y.Z`.
- `UI_API` / `ENGINE_API`: nếu giao diện/engine mới cần khung mới hơn, app giữ bản cũ và nhắc cập nhật app.

## Kỹ thuật
- [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1: đóng gói Python + yt-dlp + ffmpeg + QuickJS (JS runtime yt-dlp cần cho YouTube từ 2025.11).
- Xử lý: (1) cắt + ghép **không mã hoá** bằng concat demuxer `inpoint/outpoint` + `-c copy` → 1 file; (2) tăng tốc file đó: chia đều thành N đoạn (N = số nhân), mỗi nhân `atempo` + LAME, nối lại bằng copy. Tăng tốc bắt buộc phải mã hoá lại; muốn không mã hoá thì bật *Không mã hoá* và chỉnh tốc độ trong app nghe.
- `app/release.keystore` là khoá ký công khai chỉ để các bản build cài đè được lên nhau — **không dùng để phát hành lên Play Store**.
