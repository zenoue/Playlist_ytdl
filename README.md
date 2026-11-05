# Playlist YT → MP3 (cắt intro/outro, tăng tốc, gộp)

Chạy trên GitHub Actions: tải audio từ video/playlist YouTube → cắt đoạn đầu/cuối → tăng tốc → gộp mỗi 10 audio thành 1 file mp3.

## Dùng
1. Điền URL vào `urls.txt` (hoặc nhập trực tiếp ở ô `url` khi chạy). Mỗi dòng: `URL [cắt_đầu] [cắt_cuối]`, cho phép cắt riêng từng video/playlist.
2. Tab **Actions** → **YouTube -> MP3** → **Run workflow**, nhập: cắt đầu, cắt cuối, tốc độ, số file gộp.
3. Tải kết quả ở mục **Artifacts** của lần chạy (giữ 30 ngày), hoặc tick `release` để đăng lên Releases.

## Lỗi "Sign in to confirm you're not a bot"
IP của GitHub Actions hay bị YouTube chặn. Cách xử lý:
1. Dùng extension xuất cookies dạng Netscape (vd "Get cookies.txt LOCALLY") khi đang đăng nhập youtube.com — nên dùng tài khoản phụ.
2. Repo → Settings → Secrets and variables → Actions → New secret tên `YT_COOKIES`, dán toàn bộ nội dung cookies.txt.
3. Cookies có thể hết hạn sau vài ngày, khi đó xuất lại.

## Chạy local
```bash
pip install -U "yt-dlp[default]"   # cần thêm ffmpeg và deno
python process.py --url "https://www.youtube.com/playlist?list=..." --trim-start 0:15 --trim-end 20 --speed 1.5 --group-size 10
```
