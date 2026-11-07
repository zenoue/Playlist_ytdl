#!/usr/bin/env python3
"""
Playlist Audio — ENGINE (toàn bộ phần xử lý).

App Android tải file này từ GitHub mỗi lần chạy (có bản dự phòng đóng gói trong APK), rồi chạy bằng
Python có sẵn trong app. Sửa file này + push là app dùng code mới, KHÔNG cần build lại APK.

Giao tiếp với app qua stdout, mỗi dòng 1 lệnh:
  @@LOG <text>          ghi nhật ký
  @@STATUS <text>       dòng trạng thái
  @@PROGRESS <0..1>     thanh tiến độ
  @@FILE <path>\t<name>\t<mime>[\t<đích>]   file kết quả -> app lưu rồi xoá.
                        đích: bỏ trống = thư mục lưu người dùng chọn; "uri:<content-uri>" = ghi đè file gốc
  @@ERROR <text>        lỗi (kết thúc)
Tham số: argv[1] = file JSON = tham số giao diện (web/index.html) gửi sang + đường dẫn app thêm vào.
  mode: "playlist" (mặc định) | "tag" (gắn ảnh bìa / thông tin cho file có sẵn)
"""
ENGINE_API = 2          # app 1.x chạy engine API 2 (tham số từ giao diện chuyển thẳng sang engine)
ENGINE_VERSION = "1.3.0"

import collections
import hashlib
import json
import math
import os
import random
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

P = json.load(open(sys.argv[1], encoding="utf-8"))
for _k, _d in (("from", 1), ("count", 0), ("group", 10)):
    P[_k] = int(P.get(_k) or _d)
for _k, _d in (("trimStart", 0.0), ("trimEnd", 0.0), ("speed", 1.0)):
    P[_k] = float(P.get(_k) or _d)
for _k in ("mono", "copyMode"):
    P[_k] = bool(P.get(_k))
P.setdefault("url", "")
sys.path.insert(0, P["ytdlp"])          # file yt-dlp (zipapp) do app quản lý / tự cập nhật
import yt_dlp                            # noqa: E402

# ---------------------------------------------------------------- giao tiếp
_out = threading.Lock()


def emit(kind, *parts):
    with _out:
        sys.stdout.write("@@" + kind + " " + "\t".join(str(x).replace("\n", " ") for x in parts) + "\n")
        sys.stdout.flush()


def log(s): emit("LOG", s)
def status(s): emit("STATUS", s)
def progress(f): emit("PROGRESS", "%.4f" % max(0.0, min(1.0, f)))


T_ALL = time.time()
def secs(t0): return time.time() - t0


# ---------------------------------------------------------------- huỷ: giết cả nhóm tiến trình
CANCEL = threading.Event()
try:
    os.setpgrp()                         # ffmpeg / aria2c con cùng nhóm -> giết 1 lần là hết
except Exception:
    pass


def _on_term(*_):
    CANCEL.set()
    try:
        os.killpg(os.getpgrp(), signal.SIGKILL)
    finally:
        os._exit(130)


signal.signal(signal.SIGTERM, _on_term)


class Cancelled(Exception):
    pass


def check_cancel():
    if CANCEL.is_set():
        raise Cancelled()


# ---------------------------------------------------------------- tiện ích
CORES = max(1, os.cpu_count() or 1)


def human(bps):
    if bps >= 1048576: return "%.1f MB" % (bps / 1048576)
    if bps >= 1024: return "%.0f KB" % (bps / 1024)
    return "%.0f B" % bps


def sanitize(s):
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]', " ", s or "")
    return re.sub(r"\s+", " ", s).strip() or "audio"


def q(path):                              # escape cho file danh sách của concat demuxer
    return path.replace("'", "'\\''")


def atempo(speed):
    parts = []
    while speed > 2.0: parts.append(2.0); speed /= 2.0
    while speed < 0.5: parts.append(0.5); speed /= 0.5
    parts.append(speed)
    return ",".join("atempo=%.6f" % x for x in parts)


def ffmpeg(args, on_time=None):
    """Chạy ffmpeg. on_time(giây_đã_xuất) nếu cần tiến độ."""
    cmd = [P["ffmpeg"], "-hide_banner", "-nostdin", "-y", "-loglevel", "error"]
    if on_time:
        cmd += args[:-1] + ["-progress", "pipe:1", "-nostats", args[-1]]
    else:
        cmd += args
    pr = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    tail = []
    for line in pr.stdout:
        line = line.strip()
        if line.startswith("out_time_us="):
            if on_time:
                try: on_time(int(line[12:]) / 1e6)
                except ValueError: pass
        elif "=" not in line and line:
            tail = (tail + [line])[-6:]
    pr.wait()
    check_cancel()
    if pr.returncode != 0:
        raise RuntimeError("ffmpeg lỗi (%d): %s" % (pr.returncode, " | ".join(tail)[:400]))


def probe(path):
    """(thời lượng giây, codec audio) đọc từ ffmpeg -i."""
    r = subprocess.run([P["ffmpeg"], "-hide_banner", "-i", path], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, errors="replace")
    m = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", r.stdout)
    dur = int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3]) if m else 0.0
    c = re.search(r"Audio: (\w+)", r.stdout)
    return dur, (c[1] if c else "")


# ---------------------------------------------------------------- trạng thái lưu giữa các lần chạy
STATE_FILE = os.path.join(P["work"], "state.json")


def state_get(k, d=None):
    try: return json.load(open(STATE_FILE)).get(k, d)
    except Exception: return d


def state_set(k, v):
    try: s = json.load(open(STATE_FILE))
    except Exception: s = {}
    s[k] = v
    os.makedirs(P["work"], exist_ok=True)
    json.dump(s, open(STATE_FILE, "w"))


# ---------------------------------------------------------------- yt-dlp
def P_get(k, d=None):
    v = P.get(k)
    return d if v in (None, "") else v


def base_opts():
    o = {
        "quiet": True, "no_warnings": True, "noprogress": True,
        "cachedir": P["cache"], "socket_timeout": 20, "retries": 10, "fragment_retries": 10,
    }
    if P.get("qjs") and os.path.exists(P["qjs"]):
        o["js_runtimes"] = {"quickjs": {"path": P["qjs"]}}
    if P.get("url", "").startswith("file://"):
        o["enable_file_urls"] = True     # chỉ dùng khi thử nghiệm
    return o


def is_bot(msg):
    return "not a bot" in msg or "Sign in to confirm" in msg


def short(e):
    lines = [x for x in str(e).splitlines() if x.strip()]
    return (lines[-1] if lines else repr(e))[:250]


def list_entries():
    t0 = time.time()
    status("Đọc danh sách video…")
    frm, cnt = P["from"], P["count"]
    items = "%d:%d" % (frm, frm + cnt - 1) if cnt > 0 else "%d:" % frm
    with yt_dlp.YoutubeDL({**base_opts(), "extract_flat": "in_playlist", "playlist_items": items,
                           "noplaylist": False, "ignoreerrors": True}) as y:
        info = y.extract_info(P["url"], download=False)
    if not info:
        raise RuntimeError("Không đọc được link")
    is_list = info.get("_type") == "playlist"
    raw = [e for e in (info.get("entries") or []) if e] if is_list else [info]
    entries = []
    for i, e in enumerate(raw):
        entries.append({
            "n": i, "index": frm + i, "id": e.get("id"),
            "url": e.get("url") if is_list else (e.get("webpage_url") or P["url"]),
            "title": e.get("title") or e.get("id") or "audio",
            "duration": float(e.get("duration") or 0),
        })
    meta = {
        "title": info.get("title") if is_list else "",
        "uploader": info.get("uploader") or info.get("channel") or "",
    }
    if not entries:
        raise RuntimeError("Không tìm thấy video nào. Kiểm tra lại link.")
    log("Playlist: %s — %d video (%.1fs)" % (meta["title"] or "(video lẻ)", len(entries), secs(t0)))
    return entries, meta


# ---- tải: lấy thông tin lần lượt (chậm rãi như YTDLnis, tránh bị coi là bot), truyền dữ liệu song song
EXTRACT_SEM = threading.Semaphore(1)
BOT = threading.Event()
FAST_KEY = "fast_fail_at"


def fast_allowed():
    return time.time() - (state_get(FAST_KEY, 0) or 0) > 24 * 3600


def download_all(entries, wdir):
    total = len(entries)
    results = [None] * total
    expected = {}            # n -> số byte dự kiến
    done = set()
    lock = threading.Lock()
    t0 = time.time()
    first_req = [True]

    def files_of(idx):
        return [os.path.join(wdir, f) for f in os.listdir(wdir) if f.startswith("raw_%d." % idx)]

    def one(e):
        n, idx = e["n"], e["index"]
        tag = "[%d/%d]" % (n + 1, total)
        side = os.path.join(wdir, "raw_%d.json" % idx)
        if os.path.exists(side):
            try:
                r = json.load(open(side))
                if os.path.exists(r["path"]):
                    log("%s Đã có sẵn: %s" % (tag, e["title"]))
                    with lock: done.add(n)
                    results[n] = r
                    return
            except Exception:
                pass
        if BOT.is_set() or CANCEL.is_set():
            return
        fmt = ("bestaudio[ext=m4a]/bestaudio/best" if P["copyMode"]
               else "bestaudio[abr<=80]/bestaudio/best" if P["mono"]
               else "bestaudio[ext=m4a]/bestaudio/best")
        opts = {**base_opts(), "format": fmt, "noplaylist": True, "continuedl": True, "updatetime": False,
                "concurrent_fragment_downloads": 4, "writethumbnail": True,
                "outtmpl": {"default": os.path.join(wdir, "raw_%d.%%(ext)s" % idx),
                            "thumbnail": os.path.join(wdir, "thumb_%d.%%(ext)s" % idx)}}
        if P.get("aria2c"):
            # aria2c: 16 kết nối / file (mặc định của yt-dlp) -> vượt giới hạn tốc độ từng kết nối
            opts["external_downloader"] = {"default": P["aria2c"]}
            opts["external_downloader_args"] = {"aria2c": ["--console-log-level=error",
                                                           "--ca-certificate=" + P["cert"]]}
        t1 = time.time()
        fast = fast_allowed() and not P["url"].startswith("file://")
        info = None
        while info is None:
            check_cancel()
            if BOT.is_set():
                return
            o = dict(opts)
            if fast:   # client visionos: không cần chạy JavaScript (bỏ bước QuickJS chậm)
                o["extractor_args"] = {"youtube": {"player_client": ["visionos"]}}
            with EXTRACT_SEM:
                if not first_req[0]:
                    time.sleep(1 + random.random() * 2)   # nghỉ 1–3 s giữa các lần hỏi YouTube
                first_req[0] = False
                try:
                    with yt_dlp.YoutubeDL(o) as y:
                        info = y.extract_info(e["url"], download=False)
                except Exception as ex:   # noqa: BLE001
                    if fast:
                        fast = False
                        state_set(FAST_KEY, time.time())
                        log("%s Lấy thông tin nhanh lỗi → chế độ đầy đủ (%s)" % (tag, short(ex)))
                        continue
                    if is_bot(str(ex)):
                        if not BOT.is_set():
                            log("%s YouTube bắt xác minh bot → dừng gửi yêu cầu mới. Đổi mạng (wifi ↔ 4G) "
                                "hoặc chờ vài giờ rồi chạy lại." % tag)
                        BOT.set()
                        return
                    log("%s LỖI, bỏ qua: %s" % (tag, short(ex)))
                    return
            log("%s Lấy thông tin: %.1fs%s" % (tag, secs(t1), "" if fast else " (chế độ đầy đủ)"))
        fs = info.get("filesize") or info.get("filesize_approx")
        if not fs:
            for rd in info.get("requested_formats") or info.get("requested_downloads") or []:
                fs = rd.get("filesize") or rd.get("filesize_approx")
        expected[n] = fs or 0
        try:
            with yt_dlp.YoutubeDL(o) as y:
                info = y.process_ie_result(info, download=True)
        except Exception as ex:  # noqa: BLE001
            if CANCEL.is_set(): return
            if o.get("external_downloader"):
                log("%s aria2c lỗi → trình tải thường (%s)" % (tag, short(ex)))
                o.pop("external_downloader", None)
                try:
                    with yt_dlp.YoutubeDL(o) as y:
                        info = y.process_ie_result(info, download=True)
                except Exception as ex2:  # noqa: BLE001
                    log("%s LỖI tải, bỏ qua: %s" % (tag, short(ex2)))
                    return
            else:
                log("%s LỖI tải, bỏ qua: %s" % (tag, short(ex)))
                return
        rd = (info.get("requested_downloads") or [{}])[0]
        path = rd.get("filepath") or info.get("filepath")
        if not path or not os.path.exists(path):
            c = [f for f in files_of(idx) if not f.endswith((".part", ".aria2", ".json", ".ytdl"))]
            path = c[0] if c else None
        if not path:
            log("%s LỖI: không thấy file đã tải" % tag)
            return
        dur = float(info.get("duration") or 0) or probe(path)[0]
        r = {"path": path, "duration": dur}
        json.dump(r, open(side, "w"))
        results[n] = r
        with lock: done.add(n)
        log("%s Tải xong %.1fMB (%s) trong %.1fs: %s" % (
            tag, os.path.getsize(path) / 1048576, os.path.splitext(path)[1][1:], secs(t1), e["title"]))

    # theo dõi tốc độ bằng dung lượng file đang ghi (đúng cho cả aria2c lẫn trình tải thường)
    stop = threading.Event()

    def monitor():
        last_b, last_t = 0, time.time()
        while not stop.wait(0.7):
            b, part = 0, 0.0
            with lock:
                dn = set(done)
            for e in entries:
                n = e["n"]
                if n in dn:
                    continue
                s = sum(os.path.getsize(f) for f in files_of(e["index"]) if os.path.exists(f)
                        and not f.endswith((".json",)) and "thumb" not in f)
                b += s
                if expected.get(n):
                    part += min(1.0, s / expected[n])
            now = time.time()
            sp = max(0, b - last_b) / max(now - last_t, 0.1)
            last_b, last_t = b, now
            progress(0.45 * (len(dn) + part) / total)
            status("Tải %d/%d · %s/s" % (len(dn), total, human(sp)))

    status("Tải %d video…" % total)
    th = threading.Thread(target=monitor, daemon=True)
    th.start()
    with ThreadPoolExecutor(max_workers=min(10, total)) as ex:
        list(ex.map(one, entries))
    stop.set()
    check_cancel()
    ok = [dict(r, entry=entries[i]) for i, r in enumerate(results) if r]
    mb = sum(os.path.getsize(r["path"]) for r in ok) / 1048576
    log("Tải xong %d/%d video, %.0fMB trong %.1fs" % (len(ok), total, mb, secs(t0)))
    return ok


# ---------------------------------------------------------------- xoá quảng cáo lặp lại
# Kênh truyện audio hay chèn cùng 1 đoạn thu sẵn (câu quảng bá, quảng cáo) nhiều lần trong mọi video.
# Cách tìm (Python thuần, không cần numpy): đường năng lượng dB mỗi 32 ms -> băm khoảng cách giữa các đỉnh
# âm tiết + bỏ phiếu độ lệch để tìm MẪU lặp (≥3 lần) -> quét từng video theo mẫu -> cắt khi ghép (copy).
AHOP = 0.032


def envelope(path, ss, to):
    """Mức năng lượng (dB) mỗi khung 32 ms của đoạn [ss, to] (giây) trong file."""
    r = subprocess.run([P["ffmpeg"], "-v", "error", "-ss", "%.3f" % ss, "-to", "%.3f" % to, "-i", path, "-af",
                        "aresample=8000,asetnsamples=n=256:p=0,astats=metadata=1:reset=1:measure_perchannel=none:"
                        "measure_overall=RMS_level,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
                        "-f", "null", "-"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    return [max(-70.0, float(x)) if x not in ("-inf", "inf") else -70.0
            for x in re.findall(r"RMS_level=(\S+)", r.stdout)]


def _corr(x, y):
    n = len(x)
    if n < 8 or len(y) != n: return 0.0
    mx = sum(x) / n; my = sum(y) / n; sxy = sx = sy = 0.0
    for p, q in zip(x, y):
        p -= mx; q -= my; sxy += p * q; sx += p * p; sy += q * q
    return sxy / math.sqrt(sx * sy + 1e-9)


class _Rep:
    def __init__(self, e, gap_s=15.0, min_len=4.0, max_len=150.0):
        self.e = e; self.n = len(e); self.gap = int(gap_s / AHOP); self.min_len = min_len; self.max_len = max_len

    def C(self, a, b, m):
        e, n = self.e, self.n
        if a < 0 or b < 0 or a + m > n or b + m > n: return 0.0
        return _corr(e[a:a+m], e[b:b+m])

    def md(self, i, off):           # trung bình |chênh dB| trên 0.5 s
        e, n = self.e, self.n
        if i < 0 or i + off < 0 or i + 16 + off > n or i + 16 > n: return 99
        return sum(abs(e[i+k] - e[i+k+off]) for k in range(16)) / 16

    def refine(self, a, off, w=48):
        return max(((self.C(a, a + off + d, w), off + d) for d in range(-6, 7)))

    def grow(self, a, off):
        e = self.e; lo = hi = a
        while self.md(lo - 16, off) < 8: lo -= 2
        while self.md(hi, off) < 8: hi += 2
        hi += 16
        while hi > lo and abs(e[hi-1] - e[hi-1+off]) > 6: hi -= 1      # gọt mép theo từng khung
        while lo < hi and abs(e[lo] - e[lo+off]) > 6: lo += 1
        if not (self.min_len <= (hi - lo) * AHOP <= self.max_len): return None
        if self.C(lo, lo + off, hi - lo) < 0.85: return None             # kiểm tra trên CẢ đoạn
        return lo, hi

    def discover(self):
        """Tìm các mẫu lặp. Trả về [(a, b)] theo khung."""
        e, n = self.e, self.n
        s = [(e[max(0, i-1)] + e[i] + e[min(n-1, i+1)]) / 3 for i in range(n)]
        peaks = [i for i in range(8, n-8) if s[i] > -42 and s[i] == max(s[i-4:i+5]) and s[i] - min(s[i-8:i+9]) > 4]
        buckets = collections.defaultdict(list)
        for k in range(len(peaks) - 3):
            ks = set()
            for (i1, i2, i3) in ((k, k+1, k+2), (k, k+1, k+3), (k, k+2, k+3)):   # chịu mất 1 đỉnh
                d1 = (peaks[i2] - peaks[i1]) / 3; d2 = (peaks[i3] - peaks[i2]) / 3
                for q1 in {math.floor(d1), math.ceil(d1)}:                       # chịu lệch ±1 khung
                    for q2 in {math.floor(d2), math.ceil(d2)}:
                        ks.add((q1, q2))
            for key in ks: buckets[key].append(peaks[k])
        votes = collections.Counter()
        for ps in buckets.values():
            if len(ps) > 60: continue
            for i in range(len(ps)):
                for j in range(i + 1, len(ps)):
                    if ps[j] - ps[i] > self.gap: votes[((ps[j] - ps[i]) // 4, ps[i] // 31)] += 1
        by_off = collections.defaultdict(list)
        for (off, reg), v in votes.items(): by_off[off].append((reg, v))
        tps = []
        for off, regs in by_off.items():
            regs.sort(); run = []
            for reg, v in regs + [(10**9, 0)]:
                if run and reg - run[-1][0] > 3:
                    if sum(x[1] for x in run) >= 3:
                        c = (run[0][0] + run[-1][0] + 1) * 31 // 2
                        r, o = self.refine(c - 24, off * 4)
                        if r >= 0.85:
                            g = self.grow(c, o)
                            if g: tps.append(g)
                    run = []
                run.append((reg, v))
        tps.sort(); uniq = []
        for a, b in tps:
            if uniq and a < uniq[-1][1]:
                if b - a > uniq[-1][1] - uniq[-1][0]: uniq[-1] = (a, b)
                continue
            uniq.append((a, b))
        return uniq

    def scan(self, a, b, region=None):
        """Mọi chỗ khớp mẫu [a, b) (quét trên đường năng lượng thô 128 ms, so lõi giữa mẫu)."""
        e, n = self.e, self.n
        c4 = self._c4 if hasattr(self, "_c4") else [sum(e[i:i+4]) / 4 for i in range(0, n - 3, 4)]
        self._c4 = c4
        a4, b4 = a // 4, b // 4; m = b4 - a4
        k0 = a4 + m // 4; core = c4[k0: a4 + 3 * m // 4]; mc = len(core)
        if mc < 8: return []
        h = min(10, mc); head = core[:h]
        j = (region[0] // 4) if region else 0
        end = min(len(c4), region[1] // 4) if region else len(c4)
        occ = []; last = -10**9
        while j + mc <= end:
            if abs(j - k0) * 4 > self.gap and j - last > mc and _corr(head, c4[j:j+h]) > 0.6 \
                    and _corr(core, c4[j:j+mc]) > 0.8:
                r, o = self.refine(k0 * 4, (j - k0) * 4)
                if r > 0.8:
                    if region:
                        # quét theo mẫu đã biết: dời nguyên mẫu (mép đã gọt) tới chỗ khớp, kiểm tra cả đoạn
                        if self.C(a, a + o, b - a) > 0.8: occ.append((a + o, b + o)); last = j
                    else:
                        g = self.grow(k0 * 4 + mc * 2, o)
                        if g: occ.append((g[0] + o, g[1] + o)); last = j
            j += 1
        return occ

    def snap(self, a, b):
        """Đặt điểm cắt vào khoảng lặng: đầu = chỗ lặng nhất gần mép, cuối = chỗ lặng SỚM NHẤT
        (sau quảng cáo thường là câu "Chương N…" cùng giọng đọc, dễ bị tính lấn vào)."""
        e, n = self.e, self.n
        lo, hi = max(0, a - 6), min(n, a + 31)
        a2 = min(range(lo, hi), key=lambda i: e[i]) if hi > lo else a
        lo, hi = max(0, b - 56), min(n, b + 6)
        fl = min(e[lo:hi]) if hi > lo else 0
        b2 = next((i for i in range(lo, hi) if e[i] <= fl + 4), b - 1) + 1
        return (a2, b2) if b2 - a2 > (b - a) // 2 else (a, b)


def find_ads(vids):
    """vids: danh sách video (có path, duration). Gắn r["cuts"] = [(giây_đầu, giây_cuối)] theo thời gian file gốc."""
    t0 = time.time()
    status("Tìm quảng cáo lặp lại…")
    ranges = [(P["trimStart"], r["duration"] - P["trimEnd"]) for r in vids]
    with ThreadPoolExecutor(max_workers=CORES) as ex:
        envs = list(ex.map(lambda x: envelope(x[0]["path"], *x[1]), zip(vids, ranges)))
    check_cancel()
    SEP = [-70.0] * 64
    # 1) tìm mẫu trên tối đa 6 video đầu (đủ nhiều lần lặp, chạy nhanh)
    disc = []
    for ev in envs[:6]: disc += ev + SEP
    rp = _Rep(disc)
    temps = []; covered = []
    for a, b in rp.discover():
        if any(a < y and b > x for x, y in covered):   # cùng 1 quảng cáo đã có mẫu -> bỏ mẫu trùng
            continue
        occ = [(a, b)] + rp.scan(a, b)
        if len(occ) >= 3:                     # chỉ coi là quảng cáo nếu lặp ≥ 3 lần
            temps.append(disc[a:b])           # chỉ lõi mẫu (mép đã gọt), không kèm phần xung quanh
            covered += occ
    if not temps:
        log("Không thấy đoạn lặp lại nào (%.1fs)" % secs(t0))
        return 0
    log("Mẫu quảng cáo: %s" % ", ".join("%.1fs" % (len(t) * AHOP) for t in temps))
    # 2) quét từng video: đặt các mẫu ngay trước đường năng lượng của video rồi tìm chỗ khớp
    total = 0.0
    for r, ev, (ss, to) in zip(vids, envs, ranges):
        check_cancel()
        pre = []; pos = []
        for t in temps:
            pre += SEP; pos.append((len(pre), len(pre) + len(t))); pre += t
        pre += SEP
        arr = pre + ev; rp = _Rep(arr); base = len(pre)
        cuts = []
        for a, b in pos:
            for x, y in rp.scan(a, b, region=(base, len(arr))):
                x, y = rp.snap(x, y)
                cuts.append((ss + (x - base) * AHOP, ss + (y - base) * AHOP))
        cuts.sort(); merged = []
        for x, y in cuts:
            if merged and x < merged[-1][1]:
                # 2 mẫu cùng trúng 1 chỗ: giữ đoạn NGẮN hơn (thà sót chút quảng cáo còn hơn mất lời truyện)
                if y - x < merged[-1][1] - merged[-1][0]: merged[-1] = (x, y)
            else: merged.append((x, y))
        r["cuts"] = merged
        total += sum(y - x for x, y in merged)
        if merged:
            log("[%d] Xoá %d đoạn quảng cáo: %s" % (r["entry"]["n"] + 1, len(merged),
                ", ".join("%d:%02d" % divmod(int(x), 60) for x, _ in merged)))
    log("Xoá quảng cáo: tổng %.0fs trong %.1fs" % (total, secs(t0)))
    return total


def keep_parts(r):
    """Các khoảng giữ lại (giây, theo file gốc) sau khi cắt đầu/cuối và bỏ quảng cáo."""
    a, end = P["trimStart"], r["duration"] - P["trimEnd"]
    out = []
    for x, y in r.get("cuts", []):
        if x > a + 0.05: out.append((a, x))
        a = max(a, y)
    if end > a + 0.05: out.append((a, end))
    return out


# ---------------------------------------------------------------- xử lý
def keep_of(r):
    return r["duration"] - P["trimStart"] - P["trimEnd"]


def make_cover(wdir, idx):
    c = P.get("cover")
    if c and os.path.exists(c):
        out = os.path.join(wdir, "cover_custom.jpg")
        if os.path.exists(out): return out
        try:
            ffmpeg(["-i", c, "-frames:v", "1", "-vf", "scale='min(800,iw)':-2", "-q:v", "3", out])
            return out
        except Exception as e:  # noqa: BLE001
            log("Không dùng được ảnh bìa riêng: %s" % short(e))
    src = [f for f in os.listdir(wdir) if f.startswith("thumb_%d." % idx) and not f.endswith(".part")]
    if not src: return None
    out = os.path.join(wdir, "cover_%d.jpg" % idx)
    if os.path.exists(out): return out
    try:
        ffmpeg(["-i", os.path.join(wdir, src[0]), "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "3", out])
        return out
    except Exception as e:  # noqa: BLE001
        log("Không tạo được ảnh bìa: %s" % short(e))
        return None


def speed_up(src, dur, wdir, g, ngroups):
    """1 file đã ghép -> chia đều N đoạn (N = số nhân), mỗi nhân: giải mã + atempo + LAME, nối lại bằng copy."""
    n = max(1, min(CORES, math.ceil(dur / 120)))
    ln = dur / n
    total_out = dur / P["speed"]
    done = {}
    t0 = time.time()
    last = [0.0]
    flt = ("aformat=channel_layouts=mono,aresample=24000," + atempo(P["speed"])) if P["mono"] else atempo(P["speed"])
    aac = P_get("format", "m4a") != "mp3"
    br = ("64k" if P["mono"] else "128k") if not aac else ("64k" if P["mono"] else "128k")
    xt = "m4a" if aac else "mp3"
    codec = ["-c:a", "aac", "-b:a", br] if aac else ["-c:a", "libmp3lame", "-b:a", br, "-compression_level", "7"]
    status("Tăng tốc %.2fx: %.0f phút audio trên %d nhân…" % (P["speed"], dur / 60, n))
    parts = [os.path.join(wdir, "sp_%d_%02d.%s" % (g, i, xt)) for i in range(n)]

    def job(i):
        def on_t(s):
            done[i] = s
            now = time.time()
            if now - last[0] > 0.4:
                last[0] = now
                d = sum(done.values())
                progress(0.45 + 0.55 * (g + 0.9 * d / total_out) / ngroups)
                status("Tăng tốc %d/%d: %.0f%% · %.0fx realtime · %d nhân" % (
                    g + 1, ngroups, 100 * d / total_out, d / max(secs(t0), 0.1), n))
        ffmpeg(["-threads", "1", "-ss", "%.3f" % (i * ln), "-t", "%.3f" % ln, "-i", src, "-vn", "-af", flt,
                *codec, parts[i]], on_t)

    with ThreadPoolExecutor(max_workers=n) as ex:
        list(ex.map(job, range(n)))
    lst = os.path.join(wdir, "sp_%d.txt" % g)
    open(lst, "w", encoding="utf-8").write("".join("file '%s'\n" % q(x) for x in parts))
    out = os.path.join(wdir, "sped_%d.%s" % (g, xt))
    ffmpeg(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", out])
    for x in parts: os.remove(x)
    log("Tăng tốc %.0f phút audio: %.1fs (%.0fx realtime)" % (total_out / 60, secs(t0), total_out / max(secs(t0), 0.1)))
    return out


# ---------------------------------------------------------------- tên file + metadata hàng loạt
# Audiobook không có chuẩn riêng -> mượn tag nhạc theo quy ước (beets-audible, Audiobookshelf):
#   Tên sách -> album (ID3 TALB / MP4 ©alb)
#   Tác giả  -> album_artist (TPE2 / aART) và artist (TPE1 / ©ART)
#   Thể loại -> genre (TCON / ©gen)
# Mỗi phần: title = "{phan}. {ten}", track = phần/tổng để trình phát xếp đúng thứ tự.
DEFAULT_TPL = {
    "nameTpl": "{sach}_{phan}_v{tu}-{den}",       # tên file (không cần đuôi)
    "titleTpl": "{phan}. {ten}",                   # tên từng phần (TIT2)
    "bookTpl": "{playlist}",                       # tên sách
    "authorTpl": "{kenh}",                         # tác giả
}


class _Keep(dict):
    def __missing__(self, k):            # biến không có thì giữ nguyên "{k}"
        return "{" + k + "}"


def render(tpl, vals):
    try:
        return tpl.format_map(_Keep(vals))
    except Exception:  # noqa: BLE001  (người dùng gõ sai ngoặc...)
        return tpl


def main():
    log("Engine %s · CPU %d nhân" % (ENGINE_VERSION, CORES))
    entries, meta = list_entries()
    check_cancel()
    key = hashlib.md5(("%s|%s|%s" % (P["url"], P["mono"], P["copyMode"])).encode()).hexdigest()[:10]
    wdir = os.path.join(P["work"], key)
    os.makedirs(wdir, exist_ok=True)
    for d in os.listdir(P["work"]):           # dọn thư mục của lần chạy khác
        full = os.path.join(P["work"], d)
        if d != key and os.path.isdir(full): shutil.rmtree(full, ignore_errors=True)

    raws = download_all(entries, wdir)
    if not raws:
        raise RuntimeError("YouTube đang chặn (bắt xác minh bot). Đổi mạng (wifi ↔ 4G) hoặc chờ vài giờ rồi chạy lại."
                           if BOT.is_set() else "Không tải được video nào")
    valid = sorted([r for r in raws if keep_of(r) > 1], key=lambda r: r["entry"]["n"])
    for r in raws:
        if keep_of(r) <= 1:
            log("[%d] Bỏ qua: video dài %ds, cắt xong không còn gì" % (r["entry"]["n"] + 1, r["duration"]))
    if not valid:
        raise RuntimeError("Không còn video nào sau khi cắt")

    if P.get("removeAds", True):
        try:
            find_ads(valid)
        except Cancelled:
            raise
        except Exception as e:  # noqa: BLE001
            log("Bỏ qua bước xoá quảng cáo (lỗi: %s)" % short(e))
    groups = [valid[i:i + P["group"]] for i in range(0, len(valid), P["group"])]
    start = int(P_get("partStart", 1))                # đánh số phần bắt đầu từ
    width = max(2, len(str(len(groups) + start - 1)))
    # tương thích tên tham số cũ (giao diện 1.1)
    P.setdefault("bookTpl", P.get("albumTpl"))
    P.setdefault("authorTpl", P.get("artistTpl"))
    tpl = {k: (P_get(k) or d) for k, d in DEFAULT_TPL.items()}
    playlist = meta["title"] or entries[0]["title"]
    channel = meta["uploader"] or playlist
    last_part = len(groups) + start - 1
    base_vals = {"kenh": channel, "playlist": playlist}
    author = render(tpl["authorTpl"], base_vals) or channel
    genre = P_get("genre", "Audiobook")
    today = time.strftime("%Y-%m-%d")
    os.makedirs(P["out"], exist_ok=True)

    for g, vids in enumerate(groups):
        check_cancel()
        first, last = vids[0]["entry"], vids[-1]["entry"]
        tg = time.time()
        # 1) Cắt + ghép KHÔNG mã hoá (concat demuxer inpoint/outpoint, -c copy) -> 1 file .mka
        status("Cắt + ghép %d/%d (không mã hoá)…" % (g + 1, len(groups)))
        lst = os.path.join(wdir, "concat_%d.txt" % g)
        open(lst, "w", encoding="utf-8").write("".join(
            "file '%s'\ninpoint %.3f\noutpoint %.3f\n" % (q(r["path"]), x, y)
            for r in vids for x, y in keep_parts(r)))
        joined = os.path.join(wdir, "joined_%d.mka" % g)
        ffmpeg(["-f", "concat", "-safe", "0", "-i", lst, "-map", "0:a", "-c", "copy", joined])
        jdur = sum(y - x for r in vids for x, y in keep_parts(r))
        log("Ghép %d video (%.0f phút) không mã hoá: %.1fs" % (len(vids), jdur / 60, secs(tg)))

        # 2) Tốc độ: chỉ đổi được khi mã hoá lại; "Không mã hoá" thì giữ nguyên (chỉnh tốc độ trong app nghe)
        codec = probe(joined)[1]
        fmt_out = P_get("format", "m4a")   # m4a (AAC) mặc định: hợp Bluetooth AAC; mp3 nếu cần tương thích cũ
        if not P["copyMode"]: ext, mime = ("mp3", "audio/mpeg") if fmt_out == "mp3" else ("m4a", "audio/mp4")
        elif codec == "aac": ext, mime = "m4a", "audio/mp4"
        elif codec == "opus": ext, mime = "opus", "audio/ogg"
        elif codec == "mp3": ext, mime = "mp3", "audio/mpeg"
        else: ext, mime = "mka", "audio/x-matroska"
        audio = joined if P["copyMode"] else speed_up(joined, jdur, wdir, g, len(groups))

        # 3) Ảnh bìa + thông tin (copy)
        part = g + start
        vals = {"playlist": playlist, "kenh": channel, "tac_gia": author, "the_loai": genre, "phan": "%0*d" % (width, part), "so": part,
                "tong": last_part, "tu": first["index"], "den": last["index"],
                "ten_dau": first["title"], "ten_cuoi": last["title"],
                "ten": first["title"] + (" → " + last["title"] if len(vids) > 1 else ""),
                "ngay": today, "ext": ext}
        # Tên sách dùng được mọi biến của phần ({phan}, {tu}, {den}, {ten_dau}…); tên file dùng được {sach}
        book = render(tpl["bookTpl"], vals) or playlist
        vals["sach"] = book
        name = sanitize(render(tpl["nameTpl"], vals))[:150]
        if not name.lower().endswith("." + ext):
            name += "." + ext
        out = os.path.join(P["out"], "final_%d.%s" % (g, ext))
        cover = None if ext in ("opus", "mka") else make_cover(wdir, first["index"])
        args = ["-i", audio]
        args += ["-i", cover, "-map", "0:a", "-map", "1:v"] if cover else ["-map", "0:a"]
        args += ["-c", "copy"] + (["-id3v2_version", "3"] if ext == "mp3" else [])
        title = render(tpl["titleTpl"], vals)
        tags = [("title", title), ("album", book), ("album_artist", author), ("artist", author),
                ("genre", genre), ("track", "%d/%d" % (part, last_part))]
        for k, v in tags:
            args += ["-metadata", "%s=%s" % (k, v)]
        if cover:
            args += ["-disposition:v", "attached_pic", "-metadata:s:v", "title=Album cover",
                     "-metadata:s:v", "comment=Cover (front)"]
        try:
            ffmpeg(args + [out])
        except Exception as e:  # noqa: BLE001
            if not cover: raise
            log("Không gắn được ảnh bìa: %s" % short(e))
            ffmpeg(["-i", audio, "-map", "0:a", "-c", "copy", out])
        emit("FILE", out, name, mime)            # app lưu file rồi xoá
        for x in {joined, audio}:
            if os.path.exists(x): os.remove(x)
        log("Phần %d xong: %.1fs" % (g + 1, secs(tg)))
        progress(0.45 + 0.55 * (g + 1) / len(groups))

    shutil.rmtree(wdir, ignore_errors=True)
    progress(1.0)
    log("TỔNG THỜI GIAN: %.1fs" % secs(T_ALL))


MIME = {"mp3": "audio/mpeg", "m4a": "audio/mp4", "mp4": "audio/mp4", "opus": "audio/ogg", "ogg": "audio/ogg",
        "flac": "audio/flac", "wav": "audio/wav", "mka": "audio/x-matroska", "webm": "audio/webm"}


def tag_mode():
    """Gắn ảnh bìa + thông tin cho 1 file có sẵn (chỉ copy, không mã hoá lại)."""
    src = P["input"]
    wdir = os.path.join(P["work"], "tag")
    shutil.rmtree(wdir, ignore_errors=True)
    os.makedirs(wdir, exist_ok=True)
    os.makedirs(P["out"], exist_ok=True)
    cover_src = None
    cs = P.get("coverSource", "keep")
    if cs == "yt":
        status("Lấy thumbnail từ YouTube…")
        link = P.get("link", "")
        o = {**base_opts(), "skip_download": True, "writethumbnail": True,
             "outtmpl": {"default": os.path.join(wdir, "v.%(ext)s"), "thumbnail": os.path.join(wdir, "thumb.%(ext)s")},
             "extractor_args": {"youtube": {"player_client": ["visionos"]}}}
        if "list=" in link:
            o.update({"noplaylist": False, "playlist_items": str(int(P.get("index") or 1))})
        else:
            o["noplaylist"] = True
        with yt_dlp.YoutubeDL(o) as y:
            y.extract_info(link, download=True)
        c = [f for f in os.listdir(wdir) if f.startswith("thumb.")]
        if not c:
            raise RuntimeError("Không lấy được thumbnail")
        cover_src = os.path.join(wdir, c[0])
    elif cs == "image":
        cover_src = P.get("coverPath")
    cover = None
    if cover_src:
        cover = os.path.join(wdir, "cover.jpg")
        ffmpeg(["-i", cover_src, "-frames:v", "1", "-vf", "scale='min(800,iw)':-2", "-q:v", "3", cover])
    name = P.get("name") or os.path.basename(src)
    ext = os.path.splitext(name)[1][1:].lower() or "mp3"
    out = os.path.join(P["out"], "tagged." + ext)
    args = ["-i", src]
    args += ["-i", cover, "-map", "0:a", "-map", "1:v"] if cover else ["-map", "0"]
    args += ["-c", "copy"] + (["-id3v2_version", "3"] if ext == "mp3" else [])
    for k in ("title", "album", "artist", "track", "genre"):
        if k in P:
            args += ["-metadata", "%s=%s" % (k, P.get(k, ""))]
    if "artist" in P:                     # tác giả -> cả album_artist (TPE2) theo quy ước audiobook
        args += ["-metadata", "album_artist=%s" % P.get("artist", "")]
    if cover:
        args += ["-disposition:v", "attached_pic", "-metadata:s:v", "title=Album cover",
                 "-metadata:s:v", "comment=Cover (front)"]
    status("Ghi thông tin…")
    ffmpeg(args + [out])
    emit("FILE", out, name, MIME.get(ext, "audio/mpeg"), ("uri:" + P["inputUri"]) if P.get("inputUri") else "")
    progress(1.0)
    log("Đã gắn ảnh bìa / thông tin: %s" % name)


if __name__ == "__main__":
    try:
        tag_mode() if P.get("mode") == "tag" else main()
    except Cancelled:
        emit("ERROR", "Đã dừng")
        sys.exit(130)
    except Exception as e:  # noqa: BLE001
        emit("ERROR", short(e))
        sys.exit(1)
