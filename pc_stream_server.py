#!/usr/bin/env python3
"""
AVATAR PC stream server — receiver + live dashboard.

Port 9500 does BOTH jobs. The first bytes of each connection decide which:

  * the watch speaks raw newline-delimited JSON  -> recorded + fed to the dashboard
  * a browser speaks HTTP                        -> served the live dashboard

So:
    python pc_stream_server.py
    ... then open http://localhost:9500 in a browser.

Every line the watch sends is appended to  ./stream_logs/avatar_<session>.jsonl
— the same filename the watch uses locally, so the two are trivially paired.
The file is flushed per line: `tail -f` / Notepad++ show it growing in real time.

Press Ctrl+C to stop.
"""
import json
import math
import os
import socket
import threading
import time
from collections import Counter, deque

HOST, PORT = "0.0.0.0", 9500
LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stream_logs")
MAX_POINTS = 6000          # per-sensor ring buffer held for the dashboard
RATE_WINDOW = 200          # lines used to compute the live msg/s figure


# --------------------------------------------------------------------------
# Shared state: written by the watch-connection threads, read by HTTP threads.
# --------------------------------------------------------------------------
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.seq = 0
        self.series = {"eda": deque(maxlen=MAX_POINTS),
                       "hr": deque(maxlen=MAX_POINTS),
                       "ibi": deque(maxlen=MAX_POINTS),
                       "accel": deque(maxlen=MAX_POINTS),
                       "ppg_green": deque(maxlen=MAX_POINTS),
                       "ppg_red": deque(maxlen=MAX_POINTS),
                       "ppg_ir": deque(maxlen=MAX_POINTS),
                       "skintemp": deque(maxlen=MAX_POINTS)}
        self.counts = Counter()
        self.recent = deque(maxlen=RATE_WINDOW)   # arrival times, for msg/s
        self.connected = False
        self.session = None
        self.files = {}        # session id -> open file handle
        self.file_paths = []   # display order, newest last
        self.total = 0

    # -- writers ------------------------------------------------------------
    def add(self, key, ts, value):
        self.seq += 1
        self.series[key].append((self.seq, ts, value))

    def file_for(self, session):
        """Lazily open (append) the PC-side mirror file for a session."""
        name = session or "unknown"
        f = self.files.get(name)
        if f is None:
            os.makedirs(LOG_DIR, exist_ok=True)
            path = os.path.join(LOG_DIR, f"avatar_{name}.jsonl")
            f = open(path, "a", encoding="utf-8")
            self.files[name] = f
            self.file_paths.append(path)
            print(f"  [recording] {path}")
        return f

    # -- reader -------------------------------------------------------------
    def snapshot(self, since):
        with self.lock:
            out = {}
            for key, buf in self.series.items():
                out[key] = [[s, ts, v] for (s, ts, v) in buf if s > since]
            now = time.time()
            rate = 0.0
            if len(self.recent) > 1:
                span = self.recent[-1] - self.recent[0]
                if span > 0 and now - self.recent[-1] < 3:
                    rate = (len(self.recent) - 1) / span
            return {
                "seq": self.seq,
                "connected": self.connected,
                "session": self.session,
                "file": self.file_paths[-1] if self.file_paths else None,
                "total": self.total,
                "rate": round(rate, 1),
                "counts": dict(self.counts),
                "series": out,
            }


state = State()


# --------------------------------------------------------------------------
# Watch connection: record every line, extract plottable values.
# --------------------------------------------------------------------------
def parse_float(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def handle_line(line):
    """Mirror one raw JSON line to disk and fold it into the dashboard state."""
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        print(f"  [bad line] {line[:120]}")
        return

    with state.lock:
        session = obj.get("session")
        if session:
            state.session = session
        f = state.file_for(session)
        f.write(line + "\n")
        f.flush()          # flush per line so the file is readable live

        state.total += 1
        state.recent.append(time.time())

        kind = obj.get("sensor") or obj.get("type", "?")
        state.counts[kind] += 1

        if obj.get("type") != "reading":
            return
        # Plot against sdkTs: the sample's own SDK clock. Accel arrives in
        # ~300-sample batches sharing one recvTs, so recvTs collapses a whole
        # batch into a spike; sdkTs spreads it over its true ~12 s window and
        # puts every sensor on one common time axis.
        ts = obj.get("sdkTs") or obj.get("ts") or int(time.time() * 1000)
        if kind == "EDA":
            v = parse_float(obj.get("skinConductance"))
            if v is not None:
                state.add("eda", ts, v)
        elif kind == "HeartRate":
            v = parse_float(obj.get("hr"))
            if v is not None and v > 0:
                state.add("hr", ts, v)
            # "ibi" is a STRING holding a JSON array, e.g. "[962]", often "[]".
            # Forward each raw interval; the dashboard cleans and computes HRV.
            try:
                intervals = json.loads(obj.get("ibi") or "[]")
            except (json.JSONDecodeError, TypeError):
                intervals = []
            for ms in intervals:
                ms = parse_float(ms)
                if ms is not None:
                    state.add("ibi", ts, ms)
        elif kind == "Accelerometer":
            x = parse_float(obj.get("x"))
            y = parse_float(obj.get("y"))
            z = parse_float(obj.get("z"))
            if None not in (x, y, z):
                state.add("accel", ts, [x, y, z])
        elif kind == "PPG":
            # Combined PPG_CONTINUOUS record: three channels per sample.
            for field, key in (("green", "ppg_green"), ("red", "ppg_red"), ("ir", "ppg_ir")):
                v = parse_float(obj.get(field))
                if v is not None:
                    state.add(key, ts, v)
        elif kind in ("PPG_GREEN", "PPG_RED", "PPG_IR"):
            # Legacy per-channel PPG trackers (capability fallback shape).
            v = parse_float(obj.get("value"))
            if v is not None:
                state.add("ppg_" + kind[4:].lower(), ts, v)
        elif kind == "SkinTemperature":
            skin = parse_float(obj.get("skinTemp"))
            amb = parse_float(obj.get("ambientTemp"))
            if None not in (skin, amb):
                state.add("skintemp", ts, [skin, amb])


def serve_watch(conn, addr, first):
    print(f"--- watch connected from {addr[0]} ---")
    with state.lock:
        state.connected = True
    buf = first
    try:
        with conn:
            while True:
                while b"\n" in buf:
                    raw, buf = buf.split(b"\n", 1)
                    line = raw.decode("utf-8", "replace").strip()
                    if line:
                        handle_line(line)
                chunk = conn.recv(65536)
                if not chunk:
                    break
                buf += chunk
    except (ConnectionResetError, ConnectionAbortedError, OSError):
        pass
    finally:
        with state.lock:
            state.connected = False
            summary = ", ".join(f"{k}={v}" for k, v in state.counts.items()) or "none"
            total = state.total
        print(f"--- watch disconnected: {total} lines total  [{summary}] ---")


# --------------------------------------------------------------------------
# HTTP: dashboard page + polling API. Hand-rolled, since we own the socket.
# --------------------------------------------------------------------------
def http_respond(conn, body, ctype="text/html; charset=utf-8", status="200 OK"):
    if isinstance(body, str):
        body = body.encode("utf-8")
    head = (f"HTTP/1.1 {status}\r\n"
            f"Content-Type: {ctype}\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Cache-Control: no-store\r\n"
            "Connection: close\r\n\r\n")
    conn.sendall(head.encode("ascii") + body)


def serve_http(conn, first):
    try:
        with conn:
            data = first
            while b"\r\n\r\n" not in data and len(data) < 16384:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                data += chunk
            request_line = data.split(b"\r\n", 1)[0].decode("latin-1")
            parts = request_line.split(" ")
            path = parts[1] if len(parts) > 1 else "/"

            if path.startswith("/api/data"):
                since = 0
                if "?" in path:
                    for kv in path.split("?", 1)[1].split("&"):
                        if kv.startswith("since="):
                            try:
                                since = int(kv[6:])
                            except ValueError:
                                since = 0
                http_respond(conn, json.dumps(state.snapshot(since)),
                             "application/json")
            elif path == "/" or path.startswith("/?"):
                http_respond(conn, DASHBOARD_HTML)
            else:
                http_respond(conn, "not found", "text/plain", "404 Not Found")
    except OSError:
        pass


# --------------------------------------------------------------------------
def dispatch(conn, addr):
    try:
        conn.settimeout(30)
        first = conn.recv(65536)
        conn.settimeout(None)
    except OSError:
        conn.close()
        return
    if not first:
        conn.close()
        return
    verb = first.split(b" ", 1)[0]
    if verb in (b"GET", b"HEAD", b"POST", b"OPTIONS"):
        serve_http(conn, first)
    else:
        serve_watch(conn, addr, first)


def serve():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(8)
    print(f"AVATAR stream server listening on :{PORT}")
    print(f"  dashboard : http://localhost:{PORT}")
    print(f"  recording : {LOG_DIR}\\avatar_<session>.jsonl")
    print("Waiting for the watch to connect... (start monitoring on the watch)\n")
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=dispatch, args=(conn, addr), daemon=True).start()


# --------------------------------------------------------------------------
# Dashboard. Self-contained: no CDN, no build step, works offline.
# Palette: Anthropic data-viz reference slots 1-3 (blue / green / magenta),
# stepped per mode; validated all-pairs in both modes.
# --------------------------------------------------------------------------
DASHBOARD_HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AVATAR — live sensor stream</title>
<style>
  :root {
    color-scheme: light;
    --surface-1: #fcfcfb;
    --plane: #f9f9f7;
    --text-primary: #0b0b0b;
    --text-secondary: #52514e;
    --muted: #898781;
    --grid: #e1e0d9;
    --axis: #c3c2b7;
    --border: rgba(11,11,11,0.10);
    --series-eda: #2a78d6;
    --series-hr: #008300;
    --series-hrv: #8a63d2;
    --series-ax: #d03b3b;
    --series-ay: #008300;
    --series-az: #2a78d6;
    --series-amag: #e87ba4;
    --series-ppg-g: #008300;
    --series-ppg-r: #d03b3b;
    --series-ppg-ir: #7a6bd6;
    --series-st-skin: #d97706;
    --series-st-amb: #2a78d6;
    --series-st-diff: #8a63d2;
    --good: #0ca30c;
    --critical: #d03b3b;
  }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) {
      color-scheme: dark;
      --surface-1: #1a1a19;
      --plane: #0d0d0d;
      --text-primary: #ffffff;
      --text-secondary: #c3c2b7;
      --muted: #898781;
      --grid: #2c2c2a;
      --axis: #383835;
      --border: rgba(255,255,255,0.10);
      --series-eda: #3987e5;
      --series-hr: #00a300;
      --series-hrv: #9a75e0;
      --series-ax: #e05555;
      --series-ay: #00a300;
      --series-az: #3987e5;
      --series-amag: #d55181;
      --series-ppg-g: #00a300;
      --series-ppg-r: #e05555;
      --series-ppg-ir: #9a8fe0;
      --series-st-skin: #efa03a;
      --series-st-amb: #3987e5;
      --series-st-diff: #9a75e0;
    }
  }
  :root[data-theme="dark"] {
    color-scheme: dark;
    --surface-1: #1a1a19; --plane: #0d0d0d;
    --text-primary: #ffffff; --text-secondary: #c3c2b7; --muted: #898781;
    --grid: #2c2c2a; --axis: #383835; --border: rgba(255,255,255,0.10);
    --series-eda: #3987e5; --series-hr: #00a300; --series-hrv: #9a75e0;
    --series-ax: #e05555; --series-ay: #00a300; --series-az: #3987e5; --series-amag: #d55181;
    --series-ppg-g: #00a300; --series-ppg-r: #e05555; --series-ppg-ir: #9a8fe0;
    --series-st-skin: #efa03a; --series-st-amb: #3987e5; --series-st-diff: #9a75e0;
  }

  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 20px;
    background: var(--plane); color: var(--text-primary);
    font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  header { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 4px; }
  h1 { font-size: 19px; font-weight: 600; margin: 0; letter-spacing: -0.01em; }
  .status {
    display: inline-flex; align-items: center; gap: 6px;
    font-size: 12px; font-weight: 600; padding: 3px 9px; border-radius: 999px;
    border: 1px solid var(--border); color: var(--text-secondary);
  }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); }
  .status.live .dot { background: var(--good); }
  .status.live { color: var(--good); }
  .status.idle .dot { background: var(--critical); }

  .meta {
    color: var(--text-secondary); font-size: 12.5px; margin: 6px 0 16px;
    display: flex; gap: 18px; flex-wrap: wrap;
  }
  .meta b { font-weight: 600; color: var(--text-primary); font-variant-numeric: tabular-nums; }
  .meta code {
    font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
    font-size: 12px; color: var(--text-primary);
  }

  .controls { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
  .controls .label { color: var(--muted); font-size: 12px; margin-right: 2px; }
  button {
    font: inherit; font-size: 12.5px; padding: 4px 11px; border-radius: 7px;
    border: 1px solid var(--border); background: var(--surface-1);
    color: var(--text-secondary); cursor: pointer;
  }
  button:hover { color: var(--text-primary); }
  button[aria-pressed="true"] { color: var(--text-primary); font-weight: 600; border-color: var(--axis); }
  .spacer { flex: 1; }

  .cards { display: grid; gap: 14px; grid-template-columns: 1fr; }
  @media (min-width: 1100px) { .cards { grid-template-columns: 1fr 1fr; } }

  .card {
    background: var(--surface-1); border: 1px solid var(--border);
    border-radius: 12px; padding: 14px 16px 8px;
  }
  .card.wide { grid-column: 1 / -1; }
  .card-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 2px; }
  .swatch { width: 9px; height: 9px; border-radius: 2px; flex: none; align-self: center; }
  .card h2 { font-size: 13px; font-weight: 600; margin: 0; letter-spacing: 0.01em; }
  .card .unit { font-size: 12px; color: var(--muted); }
  .card .n { margin-left: auto; font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .value {
    font-size: 34px; font-weight: 600; letter-spacing: -0.02em; line-height: 1.15;
    margin: 2px 0 6px;
  }
  .value .u { font-size: 14px; font-weight: 500; color: var(--text-secondary); margin-left: 3px; }
  .value.empty { color: var(--muted); font-size: 20px; font-weight: 500; }

  .legend { display: flex; gap: 6px; flex-wrap: wrap; margin: 0 0 6px; }
  .legend button {
    display: inline-flex; align-items: center; gap: 6px;
    font-size: 12px; padding: 2px 9px; border-radius: 999px;
  }
  .legend .swatch { width: 8px; height: 8px; }
  .legend button[aria-pressed="false"] { opacity: 0.45; }
  .legend button[aria-pressed="false"] .swatch { background: var(--muted) !important; }
  .rej { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }

  .plot { position: relative; }
  canvas { display: block; width: 100%; height: 170px; }
  .tip {
    position: absolute; pointer-events: none; opacity: 0;
    background: var(--surface-1); border: 1px solid var(--border);
    border-radius: 7px; padding: 5px 8px; font-size: 12px; white-space: nowrap;
    box-shadow: 0 3px 12px rgba(0,0,0,0.14); transition: opacity .08s;
    font-variant-numeric: tabular-nums; z-index: 2;
  }
  .tip .t { color: var(--muted); font-size: 11px; }

  table { border-collapse: collapse; width: 100%; font-size: 12.5px; font-variant-numeric: tabular-nums; }
  th, td { text-align: right; padding: 4px 8px; border-bottom: 1px solid var(--grid); }
  th:first-child, td:first-child { text-align: left; }
  th { color: var(--muted); font-weight: 500; }
  #tableView { margin-top: 14px; }
  [hidden] { display: none !important; }
  .hint { color: var(--muted); font-size: 12px; padding: 22px 0 26px; text-align: center; }
</style>
</head>
<body>

<header>
  <h1>AVATAR — live sensor stream</h1>
  <span class="status" id="status"><span class="dot"></span><span id="statusText">connecting…</span></span>
</header>

<div class="meta">
  <span>session <b id="mSession">—</b></span>
  <span><b id="mTotal">0</b> lines</span>
  <span><b id="mRate">0</b> msg/s</span>
  <span>recording to <code id="mFile">—</code></span>
</div>

<div class="controls">
  <span class="label">Window</span>
  <button data-win="30">30 s</button>
  <button data-win="120">2 min</button>
  <button data-win="600">10 min</button>
  <button data-win="0">All</button>
  <span class="spacer"></span>
  <button id="tableBtn" aria-pressed="false">Table view</button>
  <button id="themeBtn">Theme</button>
</div>

<div class="cards" id="cards">
  <div class="card wide" id="card-eda">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-eda)"></span>
      <h2>Electrodermal activity</h2><span class="unit">skin conductance, µS</span>
      <span class="n" id="n-eda">0 samples</span>
    </div>
    <div class="value empty" id="v-eda">waiting…</div>
    <div class="plot"><canvas id="c-eda"></canvas><div class="tip" id="tip-eda"></div></div>
  </div>

  <div class="card" id="card-hr">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-hr)"></span>
      <h2>Heart rate</h2><span class="unit">bpm</span>
      <span class="n" id="n-hr">0 samples</span>
    </div>
    <div class="value empty" id="v-hr">waiting…</div>
    <div class="plot"><canvas id="c-hr"></canvas><div class="tip" id="tip-hr"></div></div>
  </div>

  <div class="card" id="card-hrv">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-hrv)"></span>
      <h2>Heart rate variability</h2><span class="unit">RMSSD, ms</span>
      <span class="rej" id="hrvRejected" title="IBI intervals rejected as artifacts">0 rejected</span>
      <span class="n" id="n-hrv">0 samples</span>
    </div>
    <div class="value empty" id="v-hrv">waiting…</div>
    <div class="plot"><canvas id="c-hrv"></canvas><div class="tip" id="tip-hrv"></div></div>
  </div>

  <div class="card" id="card-skintemp">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-st-skin)"></span>
      <h2>Skin temperature</h2><span class="unit">°C</span>
      <span class="n" id="n-skintemp">0 samples</span>
    </div>
    <div class="value empty" id="v-skintemp">waiting…</div>
    <div class="legend" id="legend-skintemp"></div>
    <div class="plot"><canvas id="c-skintemp"></canvas><div class="tip" id="tip-skintemp"></div></div>
  </div>

  <div class="card wide" id="card-accel">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-amag)"></span>
      <h2>Accelerometer</h2><span class="unit">g</span>
      <span class="n" id="n-accel">0 samples</span>
    </div>
    <div class="value empty" id="v-accel">waiting…</div>
    <div class="legend" id="legend-accel"></div>
    <div class="plot"><canvas id="c-accel"></canvas><div class="tip" id="tip-accel"></div></div>
  </div>

  <div class="card wide" id="card-ppg">
    <div class="card-head">
      <span class="swatch" style="background:var(--series-ppg-g)"></span>
      <h2>PPG</h2><span class="unit">raw counts — detrended for display</span>
      <span class="n" id="n-ppg">0 samples</span>
    </div>
    <div class="value empty" id="v-ppg">waiting…</div>
    <div class="legend" id="legend-ppg"></div>
    <div class="plot"><canvas id="c-ppg"></canvas><div class="tip" id="tip-ppg"></div></div>
  </div>
</div>

<div id="tableView" hidden>
  <table>
    <thead><tr><th>Sensor</th><th>Samples</th><th>Latest</th><th>Min</th><th>Mean</th><th>Max</th></tr></thead>
    <tbody id="tableBody"></tbody>
  </table>
</div>

<p class="hint" id="hint">No data yet — start <b>Monitor All</b> on the watch.</p>

<script>
/* ---------- tuning constants ---------- */
const ACCEL_COUNTS_PER_G = 4096;   // raw accel counts per 1 g
const IBI_MIN_MS = 300;            // physiological IBI range
const IBI_MAX_MS = 1500;
const IBI_JUMP_FRAC = 0.30;        // max fractional jump vs running median of last 6
const HRV_WINDOW_S = 30;           // RMSSD sliding window
const HRV_STEP_S = 2;              // RMSSD emission cadence
const PPG_HZ = 25;                 // PPG sample rate
const PPG_DETREND_S = 2;           // rolling-mean window for display detrend (~0.5 Hz high-pass)

// Each trace names its own data key; panels group traces onto one chart/y-scale.
// `main` = the data key used for the sample counter and big-number readout (default: key);
// `big` = accessor for the big-number value (default: p=>p.v).
const PANELS = [
  {key:'eda', unit:'µS', digits:3,
   traces:[{key:'eda', label:'EDA', color:'--series-eda', getV:p=>p.v, on:true}]},
  {key:'hr', unit:'bpm', digits:0,
   traces:[{key:'hr', label:'Heart rate', color:'--series-hr', getV:p=>p.v, on:true}]},
  {key:'hrv', unit:'ms', digits:0,
   traces:[{key:'hrv', label:'RMSSD', color:'--series-hrv', getV:p=>p.v, on:true}]},
  // Skin − Ambient: peripheral vasoconstriction under sympathetic arousal lowers skin temp
  // relative to ambient, so the gradient is more informative than either value alone.
  {key:'skintemp', unit:'°C', digits:2, big:p=>p.skin,
   traces:[{key:'skintemp', label:'Skin',    color:'--series-st-skin', getV:p=>p.skin, on:true},
           {key:'skintemp', label:'Ambient', color:'--series-st-amb',  getV:p=>p.amb,  on:true},
           {key:'skintemp', label:'Skin − Ambient', color:'--series-st-diff', getV:p=>p.diff, on:true}]},
  {key:'accel', unit:'g', digits:3, big:p=>p.mag,
   traces:[{key:'accel', label:'X', color:'--series-ax', getV:p=>p.x, on:true},
           {key:'accel', label:'Y', color:'--series-ay', getV:p=>p.y, on:true},
           {key:'accel', label:'Z', color:'--series-az', getV:p=>p.z, on:true},
           {key:'accel', label:'|xyz|', color:'--series-amag', getV:p=>p.mag, on:true}]},
  // Chart draws the DETRENDED value (p.det) so heartbeats are visible on top of the large DC
  // baseline; the big number and the stats table use the RAW value (p.raw). Display-only filter.
  {key:'ppg', unit:'counts', digits:0, main:'ppg_green', big:p=>p.raw,
   traces:[{key:'ppg_green', label:'Green', color:'--series-ppg-g', getV:p=>p.det, on:true},
           {key:'ppg_red',   label:'Red',   color:'--series-ppg-r', getV:p=>p.det, on:true},
           {key:'ppg_ir',    label:'IR',    color:'--series-ppg-ir', getV:p=>p.det, on:true}]},
];
// full-resolution client buffers; 25 Hz series need 10 min = 15k, plus headroom
const CAP = {eda:20000, hr:20000, hrv:20000, accel:40000,
             ppg_green:40000, ppg_red:40000, ppg_ir:40000, skintemp:20000};
const data = {eda:[], hr:[], hrv:[], accel:[], ppg_green:[], ppg_red:[], ppg_ir:[], skintemp:[]};

/* HRV pipeline state */
let cleanIbi = [];       // accepted intervals, {ts, ms}
let ibiRejected = 0;     // artifact counter
let nextHrvTs = null;    // next RMSSD emission time

/* PPG display-detrend state: trailing rolling mean per channel (raw data never modified) */
const ppgDetrend = {ppg_green:{buf:[], sum:0}, ppg_red:{buf:[], sum:0}, ppg_ir:{buf:[], sum:0}};

let lastSeq = 0, windowSec = 120, hover = {key:null, ts:0};

/* ---------- polling ---------- */
function push(key, pt) {
  const arr = data[key];
  arr.push(pt);
  if (arr.length > CAP[key]) arr.splice(0, arr.length - CAP[key]);
}

function resetData() {
  for (const k in data) data[k] = [];
  cleanIbi = []; ibiRejected = 0; nextHrvTs = null;
  for (const k in ppgDetrend) ppgDetrend[k] = {buf:[], sum:0};
}

// Append a PPG sample: store raw plus display-only detrended value (raw minus the trailing
// rolling mean over PPG_DETREND_S seconds — an incremental running sum, O(1) per sample).
function pushPpg(key, ts, raw) {
  const d = ppgDetrend[key];
  const N = Math.max(1, Math.round(PPG_DETREND_S * PPG_HZ));
  d.buf.push(raw); d.sum += raw;
  if (d.buf.length > N) d.sum -= d.buf.shift();
  push(key, {ts, raw, det: raw - d.sum / d.buf.length});
}

async function poll() {
  try {
    const r = await fetch('/api/data?since=' + lastSeq);
    const d = await r.json();
    if (d.seq < lastSeq) resetData();  // server restarted
    lastSeq = d.seq;
    for (const p of (d.series.eda || [])) push('eda', {ts:p[1], v:p[2]});
    for (const p of (d.series.hr  || [])) push('hr',  {ts:p[1], v:p[2]});
    for (const p of (d.series.accel || [])) {
      const x = p[2][0] / ACCEL_COUNTS_PER_G,
            y = p[2][1] / ACCEL_COUNTS_PER_G,
            z = p[2][2] / ACCEL_COUNTS_PER_G;
      push('accel', {ts:p[1], x, y, z, mag:Math.sqrt(x*x + y*y + z*z)});
    }
    for (const k of ['ppg_green', 'ppg_red', 'ppg_ir'])
      for (const p of (d.series[k] || [])) pushPpg(k, p[1], p[2]);
    for (const p of (d.series.skintemp || []))
      push('skintemp', {ts:p[1], skin:p[2][0], amb:p[2][1], diff:p[2][0] - p[2][1]});
    for (const p of (d.series.ibi || [])) ingestIbi(p[1], p[2]);
    updateHrv();
    paintMeta(d);
  } catch (e) {
    setStatus(false, 'server unreachable');
  }
  render();
}
setInterval(poll, 500);
poll();

/* ---------- HRV: clean IBI, then rolling RMSSD ---------- */
function median(a) {
  const s = a.slice().sort((x, y) => x - y), m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m-1] + s[m]) / 2;
}

function ingestIbi(ts, ms) {
  if (ms < IBI_MIN_MS || ms > IBI_MAX_MS) { ibiRejected++; return; }
  const recent = cleanIbi.slice(-6).map(p => p.ms);
  if (recent.length >= 3) {
    const med = median(recent);
    if (Math.abs(ms - med) > IBI_JUMP_FRAC * med) { ibiRejected++; return; }
  }
  cleanIbi.push({ts, ms});
  if (cleanIbi.length > CAP.hr) cleanIbi.splice(0, cleanIbi.length - CAP.hr);
}

function updateHrv() {
  if (!cleanIbi.length) return;
  const latest = cleanIbi[cleanIbi.length-1].ts;
  if (nextHrvTs === null) nextHrvTs = cleanIbi[0].ts;
  while (nextHrvTs <= latest) {
    const t = nextHrvTs;
    nextHrvTs += HRV_STEP_S * 1000;
    let i = cleanIbi.length;                                  // intervals in (t-window, t]
    while (i > 0 && cleanIbi[i-1].ts > t) i--;
    let j = i;
    while (j > 0 && cleanIbi[j-1].ts > t - HRV_WINDOW_S*1000) j--;
    const n = i - j;
    if (n < 3) continue;   // too few clean beats: emit nothing rather than mislead
    let sum = 0;
    for (let k = j+1; k < i; k++) {
      const dv = cleanIbi[k].ms - cleanIbi[k-1].ms;
      sum += dv * dv;
    }
    push('hrv', {ts:t, v:Math.sqrt(sum / (n - 1))});
  }
}

function setStatus(live, text) {
  const el = document.getElementById('status');
  el.className = 'status ' + (live ? 'live' : 'idle');
  document.getElementById('statusText').textContent = text;
}

function paintMeta(d) {
  setStatus(d.connected, d.connected ? 'watch streaming' : 'watch not connected');
  document.getElementById('mSession').textContent = d.session || '—';
  document.getElementById('mTotal').textContent = d.total.toLocaleString();
  document.getElementById('mRate').textContent = d.rate.toFixed(1);
  document.getElementById('mFile').textContent = d.file || '—';
  document.getElementById('hint').hidden = d.total > 0;
}

/* ---------- shared sdkTs domain + window filter ---------- */
// One x-domain across every panel so events line up vertically. The accel
// trace's right edge sits ~12 s behind EDA/HR: that is real batching latency.
function domain() {
  let t0 = Infinity, t1 = -Infinity;
  for (const k in data) {
    const a = data[k];
    if (a.length) { t0 = Math.min(t0, a[0].ts); t1 = Math.max(t1, a[a.length-1].ts); }
  }
  if (t1 === -Infinity) return null;
  if (windowSec) t0 = Math.max(t0, t1 - windowSec*1000);
  return {t0, t1: Math.max(t1, t0 + 1)};
}

function visible(key, t0) {
  const arr = data[key];
  let lo = 0, hi = arr.length;
  while (lo < hi) { const m = (lo+hi)>>1; if (arr[m].ts < t0) lo = m+1; else hi = m; }
  return lo ? arr.slice(lo) : arr;
}

/* ---------- drawing ---------- */
function css(v){ return getComputedStyle(document.body).getPropertyValue(v).trim(); }
function fmt(v, digits){ return v.toFixed(digits); }
function clockOf(ts){
  const d = new Date(ts);
  return d.toTimeString().slice(0,8) + '.' + String(d.getMilliseconds()).padStart(3,'0');
}

function nearestIdx(arr, ts) {
  let lo = 0, hi = arr.length - 1;
  while (lo < hi) { const m = (lo+hi)>>1; if (arr[m].ts < ts) lo = m+1; else hi = m; }
  if (lo > 0 && Math.abs(arr[lo-1].ts - ts) < Math.abs(arr[lo].ts - ts)) lo--;
  return lo;
}

function drawChart(panel, dom) {
  const canvas = document.getElementById('c-' + panel.key);
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== Math.round(w*dpr)) { canvas.width = Math.round(w*dpr); canvas.height = Math.round(h*dpr); }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);

  const padL = 52, padR = 10, padT = 10, padB = 20;
  const plotW = w - padL - padR, plotH = h - padT - padB;

  ctx.strokeStyle = css('--axis'); ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(padL, padT+plotH+0.5); ctx.lineTo(padL+plotW, padT+plotH+0.5); ctx.stroke();
  const traces = panel.traces.filter(t => t.on);
  const traceData = dom
    ? traces.map(t => ({t, pts: visible(t.key, dom.t0)})).filter(td => td.pts.length >= 2)
    : [];
  if (!traceData.length) { canvas._map = null; return; }

  let min = Infinity, max = -Infinity;
  for (const {t, pts} of traceData) for (const p of pts) {
    const v = t.getV(p);
    if (v < min) min = v;
    if (v > max) max = v;
  }
  if (max - min < 1e-9) { max += 0.5; min -= 0.5; }
  const pad = (max-min)*0.12; min -= pad; max += pad;
  const t0 = dom.t0, t1 = dom.t1, span = Math.max(1, t1-t0);
  const X = ts => padL + (ts - t0)/span * plotW;
  const invX = px => t0 + (px - padL)/plotW * span;
  const Y = v  => padT + (1 - (v-min)/(max-min)) * plotH;

  // recessive grid + y ticks
  ctx.strokeStyle = css('--grid');
  ctx.fillStyle = css('--muted');
  ctx.font = '11px system-ui, -apple-system, "Segoe UI", sans-serif';
  ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
  for (let i = 0; i <= 3; i++) {
    const v = min + (max-min)*i/3, y = Math.round(Y(v)) + 0.5;
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(padL+plotW, y); ctx.stroke();
    ctx.fillText(fmt(v, panel.digits), padL-8, y);
  }
  // x ticks: elapsed seconds, shared across panels
  ctx.textAlign = 'center'; ctx.textBaseline = 'top';
  for (let i = 0; i <= 2; i++) {
    const ts = t0 + span*i/2;
    ctx.fillText('-' + ((t1-ts)/1000).toFixed(0) + 's', X(ts), padT+plotH+6);
  }

  // min/max envelope per trace, ~2 buckets per px: drawing-only decimation,
  // stored data stays full resolution for stats and recording
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  for (const {t, pts} of traceData) {
    const color = css(t.color);
    ctx.strokeStyle = color; ctx.lineWidth = traces.length > 1 ? 1.5 : 2;
    ctx.beginPath();
    const step = Math.max(1, Math.floor(pts.length / (plotW*2)));
    let started = false;
    for (let i = 0; i < pts.length; i += step) {
      let lo = Infinity, hi = -Infinity;
      for (let j = i; j < Math.min(i+step, pts.length); j++) {
        const v = t.getV(pts[j]);
        if (v < lo) lo = v;
        if (v > hi) hi = v;
      }
      const x = X(pts[i].ts);
      if (!started) { ctx.moveTo(x, Y(lo)); started = true; } else ctx.lineTo(x, Y(lo));
      if (step > 1) ctx.lineTo(x, Y(hi));
    }
    ctx.stroke();

    // last point marker, ringed against the surface
    const last = pts[pts.length-1];
    ctx.beginPath(); ctx.arc(X(last.ts), Y(t.getV(last)), 3.5, 0, Math.PI*2);
    ctx.fillStyle = color; ctx.fill();
    ctx.strokeStyle = css('--surface-1'); ctx.lineWidth = 2; ctx.stroke();
  }

  canvas._map = {invX};

  // crosshair + tooltip: each trace shows its own sample nearest the hovered time
  const tip = document.getElementById('tip-' + panel.key);
  if (hover.key === panel.key) {
    const anchor = traceData[0].pts[nearestIdx(traceData[0].pts, hover.ts)];
    const x = X(anchor.ts);
    ctx.strokeStyle = css('--axis'); ctx.lineWidth = 1;
    ctx.setLineDash([3,3]);
    ctx.beginPath(); ctx.moveTo(x, padT); ctx.lineTo(x, padT+plotH); ctx.stroke();
    ctx.setLineDash([]);
    let html = '', yTop = padT + plotH;
    for (const {t, pts} of traceData) {
      const p = pts[nearestIdx(pts, hover.ts)];
      const v = t.getV(p), y = Y(v);
      yTop = Math.min(yTop, y);
      ctx.beginPath(); ctx.arc(X(p.ts), y, 4.5, 0, Math.PI*2);
      ctx.fillStyle = css(t.color); ctx.fill();
      ctx.strokeStyle = css('--surface-1'); ctx.lineWidth = 2; ctx.stroke();
      html += (traceData.length > 1
                ? '<span style="color:' + css(t.color) + '">&#9632;</span> ' + t.label + ' '
                : '') +
              '<b>' + fmt(v, panel.digits) + (panel.unit ? ' ' + panel.unit : '') + '</b><br>';
    }
    tip.innerHTML = html + '<div class="t">' + clockOf(anchor.ts) + '</div>';
    tip.style.opacity = 1;
    tip.style.left = Math.min(Math.max(x - 45, 0), w - tip.offsetWidth) + 'px';
    tip.style.top = Math.max(yTop - tip.offsetHeight - 12, 0) + 'px';
  } else {
    tip.style.opacity = 0;
  }
}

for (const panel of PANELS) {
  const canvas = document.getElementById('c-' + panel.key);
  canvas.addEventListener('mousemove', e => {          // hit target = whole plot column
    const m = canvas._map;
    if (!m) return;
    const px = e.clientX - canvas.getBoundingClientRect().left;
    hover = {key: panel.key, ts: m.invX(px)};
    render();
  });
  canvas.addEventListener('mouseleave', () => { hover = {key:null, ts:0}; render(); });
}

/* legend with per-trace show/hide for multi-trace panels */
for (const panel of PANELS) {
  const box = document.getElementById('legend-' + panel.key);
  if (!box || panel.traces.length < 2) continue;
  for (const t of panel.traces) {
    const b = document.createElement('button');
    b.setAttribute('aria-pressed', 'true');
    b.innerHTML = '<span class="swatch" style="background:var(' + t.color + ')"></span>' + t.label;
    b.addEventListener('click', () => {
      t.on = !t.on;
      b.setAttribute('aria-pressed', String(t.on));
      render();
    });
    box.appendChild(b);
  }
}

function render() {
  const dom = domain();
  for (const panel of PANELS) {
    const arr = data[panel.main || panel.key];
    document.getElementById('n-' + panel.key).textContent = arr.length.toLocaleString() + ' samples';
    const el = document.getElementById('v-' + panel.key);
    if (arr.length) {
      el.className = 'value';
      const v = (panel.big || (p => p.v))(arr[arr.length-1]);
      el.innerHTML = fmt(v, panel.digits) +
                     (panel.unit ? '<span class="u">' + panel.unit + '</span>' : '');
    }
    drawChart(panel, dom);
  }
  document.getElementById('hrvRejected').textContent = ibiRejected.toLocaleString() + ' rejected';
  if (!document.getElementById('tableView').hidden) renderTable();
}

function renderTable() {
  const dom = domain();
  const spec = [
    {label:'EDA µS',          key:'eda',   getV:p=>p.v,   digits:3},
    {label:'Heart rate bpm',  key:'hr',    getV:p=>p.v,   digits:0},
    {label:'HRV RMSSD ms &middot; ' + ibiRejected + ' IBI rejected',
                              key:'hrv',   getV:p=>p.v,   digits:0},
    {label:'Accel X g',       key:'accel', getV:p=>p.x,   digits:3},
    {label:'Accel Y g',       key:'accel', getV:p=>p.y,   digits:3},
    {label:'Accel Z g',       key:'accel', getV:p=>p.z,   digits:3},
    {label:'Accel |xyz| g',   key:'accel', getV:p=>p.mag, digits:3},
    // RAW unfiltered counts — the display detrend never reaches the table.
    {label:'PPG green raw',   key:'ppg_green', getV:p=>p.raw, digits:0},
    {label:'PPG red raw',     key:'ppg_red',   getV:p=>p.raw, digits:0},
    {label:'PPG IR raw',      key:'ppg_ir',    getV:p=>p.raw, digits:0},
    {label:'Skin temp °C',    key:'skintemp',  getV:p=>p.skin, digits:2},
    {label:'Ambient °C',      key:'skintemp',  getV:p=>p.amb,  digits:2},
  ];
  const rows = spec.map(r => {
    const pts = dom ? visible(r.key, dom.t0) : [];
    if (!pts.length) return '<tr><td>' + r.label + '</td><td>0</td><td>—</td><td>—</td><td>—</td><td>—</td></tr>';
    let min = Infinity, max = -Infinity, sum = 0;
    for (const p of pts) {
      const v = r.getV(p);
      min = Math.min(min, v); max = Math.max(max, v); sum += v;
    }
    const f = v => fmt(v, r.digits);
    return '<tr><td>' + r.label + '</td><td>' + pts.length.toLocaleString() + '</td><td>' +
      f(r.getV(pts[pts.length-1])) + '</td><td>' + f(min) + '</td><td>' + f(sum/pts.length) +
      '</td><td>' + f(max) + '</td></tr>';
  });
  document.getElementById('tableBody').innerHTML = rows.join('');
}

/* ---------- controls ---------- */
document.querySelectorAll('[data-win]').forEach(b => {
  b.setAttribute('aria-pressed', String(+b.dataset.win === windowSec));
  b.addEventListener('click', () => {
    windowSec = +b.dataset.win;
    document.querySelectorAll('[data-win]').forEach(o =>
      o.setAttribute('aria-pressed', String(o === b)));
    render();
  });
});
document.getElementById('tableBtn').addEventListener('click', e => {
  const tv = document.getElementById('tableView');
  tv.hidden = !tv.hidden;
  e.target.setAttribute('aria-pressed', String(!tv.hidden));
  render();
});
document.getElementById('themeBtn').addEventListener('click', () => {
  const dark = matchMedia('(prefers-color-scheme: dark)').matches;
  const cur = document.documentElement.getAttribute('data-theme') || (dark ? 'dark' : 'light');
  document.documentElement.setAttribute('data-theme', cur === 'dark' ? 'light' : 'dark');
  render();
});
addEventListener('resize', render);
</script>
</body>
</html>
"""


if __name__ == "__main__":
    try:
        serve()
    except KeyboardInterrupt:
        print("\nserver stopped.")
