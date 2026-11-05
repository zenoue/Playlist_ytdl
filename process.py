#!/usr/bin/env python3
"""Tải audio YouTube -> cắt intro/outro -> tăng tốc -> gộp mỗi N file thành 1.

Định dạng file danh sách (mỗi dòng):
    URL [cắt_đầu] [cắt_cuối]
- URL có thể là video đơn hoặc playlist.
- cắt_đầu / cắt_cuối: giây (vd 15) hoặc mm:ss (vd 1:05). Bỏ trống = dùng mặc định.
- Dòng bắt đầu bằng # bị bỏ qua.
"""
import argparse
import json
import math
import shutil
import subprocess
import sys
from pathlib import Path


def parse_time(s: str) -> float:
    s = s.strip()
    sec = 0.0
    for part in s.split(":"):
        sec = sec * 60 + float(part)
    return sec


def run(cmd):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True)


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "json", str(path)],
        check=True, capture_output=True, text=True).stdout
    return float(json.loads(out)["format"]["duration"])


def atempo_chain(speed: float) -> str:
    """atempo an toàn: tách thành chuỗi các hệ số trong [0.5, 2.0]."""
    if speed <= 0:
        raise ValueError("speed phải > 0")
    parts = []
    while speed > 2.0:
        parts.append(2.0)
        speed /= 2.0
    while speed < 0.5:
        parts.append(0.5)
        speed /= 0.5
    parts.append(speed)
    return ",".join(f"atempo={p:.6f}" for p in parts)


def read_jobs(args):
    jobs = []
    if args.url:
        for u in args.url.split():
            jobs.append((u, args.trim_start, args.trim_end))
    else:
        for line in Path(args.list).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            f = line.split()
            start = parse_time(f[1]) if len(f) > 1 else args.trim_start
            end = parse_time(f[2]) if len(f) > 2 else args.trim_end
            jobs.append((f[0], start, end))
    return jobs


def download(jobs, dl_dir: Path, cookies: str | None, bitrate: str):
    dl_dir.mkdir(parents=True, exist_ok=True)
    for i, (url, _, _) in enumerate(jobs):
        cmd = ["yt-dlp", "-x", "--audio-format", "mp3", "--audio-quality", bitrate,
               "--yes-playlist", "--ignore-errors", "--no-overwrites",
               "--download-archive", str(dl_dir / "archive.txt"),
               "--sleep-requests", "1", "--retries", "10",
               "-o", str(dl_dir / f"{i:03d}_%(playlist_index|0)04d_%(id)s.%(ext)s")]
        if cookies:
            cmd += ["--cookies", cookies]
        cmd.append(url)
        # --ignore-errors: 1 video lỗi không làm hỏng cả batch
        subprocess.run(cmd)


def process_one(src: Path, dst: Path, start: float, end: float, speed: float, bitrate: str):
    dur = duration(src)
    keep = dur - start - end
    if keep <= 1:
        print(f"!! Bỏ qua {src.name}: dài {dur:.1f}s, cắt xong còn {keep:.1f}s", flush=True)
        return False
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-ss", f"{start:.3f}", "-t", f"{keep:.3f}", "-i", str(src),
         "-vn", "-af", atempo_chain(speed),
         "-c:a", "libmp3lame", "-b:a", bitrate, str(dst)])
    return True


def merge(files, dst: Path, bitrate: str):
    lst = dst.with_suffix(".txt")
    lst.write_text("".join(f"file '{f.resolve().as_posix()}'\n" for f in files), encoding="utf-8")
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "concat", "-safe", "0", "-i", str(lst),
         "-c:a", "libmp3lame", "-b:a", bitrate, str(dst)])
    lst.unlink()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", default="urls.txt", help="file danh sách URL")
    ap.add_argument("--url", default="", help="URL (cách nhau bởi khoảng trắng), ưu tiên hơn --list")
    ap.add_argument("--trim-start", type=parse_time, default=0, help="cắt đầu mặc định (giây hoặc mm:ss)")
    ap.add_argument("--trim-end", type=parse_time, default=0, help="cắt cuối mặc định (giây hoặc mm:ss)")
    ap.add_argument("--speed", type=float, default=1.25, help="hệ số tốc độ, vd 1.5")
    ap.add_argument("--group-size", type=int, default=10, help="số audio gộp thành 1 file")
    ap.add_argument("--bitrate", default="128k")
    ap.add_argument("--prefix", default="part", help="tiền tố tên file output")
    ap.add_argument("--cookies", default=None, help="cookies.txt (Netscape) cho YouTube")
    ap.add_argument("--work", default="work")
    ap.add_argument("--out", default="output")
    ap.add_argument("--skip-download", action="store_true", help="dùng file đã có trong work/downloads")
    args = ap.parse_args()

    if not shutil.which("ffmpeg"):
        sys.exit("Thiếu ffmpeg")

    jobs = read_jobs(args)
    if not jobs and not args.skip_download:
        sys.exit("Không có URL nào")

    work, out = Path(args.work), Path(args.out)
    dl_dir, proc_dir = work / "downloads", work / "processed"
    proc_dir.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)

    if not args.skip_download:
        download(jobs, dl_dir, args.cookies, args.bitrate.rstrip("k") + "K")

    srcs = sorted(dl_dir.glob("*.mp3"))
    if not srcs:
        sys.exit("Không tải được file nào (xem log; trên GitHub Actions thường cần cookies)")

    processed = []
    for src in srcs:
        idx = int(src.name.split("_", 1)[0])
        _, start, end = jobs[idx] if idx < len(jobs) else ("", args.trim_start, args.trim_end)
        dst = proc_dir / src.name
        if process_one(src, dst, start, end, args.speed, args.bitrate):
            processed.append(dst)

    n_groups = math.ceil(len(processed) / args.group_size)
    width = max(2, len(str(n_groups)))
    for g in range(n_groups):
        chunk = processed[g * args.group_size:(g + 1) * args.group_size]
        dst = out / f"{args.prefix}_{g + 1:0{width}d}.mp3"
        merge(chunk, dst, args.bitrate)
        print(f"==> {dst} ({len(chunk)} audio, {duration(dst) / 60:.1f} phút)", flush=True)

    print(f"Xong: {len(processed)}/{len(srcs)} audio -> {n_groups} file trong {out}/")


if __name__ == "__main__":
    main()
