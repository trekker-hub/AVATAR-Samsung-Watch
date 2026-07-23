# AVATAR watch → PC streaming

Streams every sensor line the watch writes to its local `.jsonl` file to a PC over
TCP, **live**, in the exact same newline-delimited JSON format. Streaming is additive:
sensor collection and the local file are never affected by whether the PC is connected,
or by the on/off toggle.

## How it works

```
  Watch (TCP client)                          PC (TCP server)
  ┌───────────────────────────┐               ┌────────────────────────┐
  │ sensor callbacks           │               │ avatar_stream_server.py│
  │   → DataLogger.writeLine() │  localhost    │   (or nc / snippet)    │
  │       → file (.jsonl)      │   :9500       │                        │
  │       → StreamClient.enqueue───────────────►  prints / plots lines  │
  │            (bounded queue) │  adb reverse  │                        │
  │   avatar-stream thread ────┘  tunnel       └────────────────────────┘
  └───────────────────────────┘
```

- The **watch is the TCP client**. It connects to `localhost:9500`, which
  `adb reverse tcp:9500 tcp:9500` tunnels to a server listening on the **PC**.
- Wire format: one JSON object per line, terminated with `\n` — **identical** to a line
  in the local `avatar_*.jsonl` file (`session_start`, per-sample `reading`s, events,
  `session_end`). No separate parser needed: anything that reads the `.jsonl` reads this.
- A bounded queue (`CAPACITY = 10_000`) buffers outgoing lines. If the PC is slow or gone,
  the **oldest** lines are dropped so memory stays capped.
- The sender thread **reconnects forever** (every 2 s) while streaming is enabled, so the
  PC can come and go without touching the app.

Key knobs live at the top of `StreamClient.kt`: `HOST`, `PORT`, `CAPACITY`, `RETRY_MS`,
`CONNECT_TIMEOUT_MS`.

## Prerequisites (one time)

- Watch connected to `adb` (USB, or wireless: `adb connect <watch-ip>:5555`).
- `adb devices` shows the watch.
- Python 3 on the PC (only for the snippet server below; your dashboard has its own deps).

## Test procedure

### 1. Open the tunnel
`adb reverse` makes the watch's `localhost:9500` reach the PC's `localhost:9500`.

```bash
adb reverse tcp:9500 tcp:9500
# verify:
adb reverse --list        # should list  (reverse) tcp:9500 tcp:9500
```

> Re-run `adb reverse` after any `adb disconnect`/reconnect or watch reboot — the tunnel
> does not survive them. (The app auto-reconnects; the *tunnel* does not.)

### 2. Start a PC server on port 9500

**Option A — your dashboard**
```bash
python3 avatar_stream_server.py            # full live dashboard
python3 avatar_stream_server.py --no-plot  # just print received lines
```

**Option B — no dashboard, just prove the pipe.** Save as `echo_server.py` and run
`python3 echo_server.py`:
```python
import socket
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", 9500)); srv.listen(1)
print("listening on :9500 …")
while True:
    conn, addr = srv.accept()
    print("connected:", addr)
    n = 0
    with conn, conn.makefile("r", encoding="utf-8") as f:
        for line in f:                    # one JSON object per line
            n += 1
            if n <= 5 or n % 100 == 0:    # print first few + every 100th
                print(f"[{n}] {line.rstrip()}")
    print("disconnected after", n, "lines")
```

### 3. Run the app and start monitoring
- Launch the app on the watch.
- Tap **📡 Monitor All** (or any sensor / Auto Run — every session streams).
- Within ~1 s the PC should print real `EDA` / `HeartRate` (with `IBI`) / `Accelerometer`
  lines. Accelerometer arrives in batches — **one line per sample**, each with its own
  `sdkTs`. Expected rate roughly ~27 msg/s.

### 4. Confirm the local file is untouched
Streaming is additive — the `.jsonl` is still written and flushed per line. Pull it after
a session to confirm it looks exactly as before:
```bash
adb shell run-as com.example.galaxywatch5 ls files/avatar   # session files
# (or use the pull-data GUI/scripts in the repo)
```

## Resilience checks (Stage 3)

| Action | Expected result |
|---|---|
| **Kill the PC server** mid-stream | App keeps collecting; `.jsonl` keeps growing. Logcat: `socket error (...); reconnecting in 2000ms`, repeating. |
| **Restart** `avatar_stream_server.py` | Watch auto-reconnects within a few seconds; live data resumes. **No app restart.** |
| **Toggle 📶 Streaming: OFF** (Monitor-All screen) | Sender thread + socket close, reconnect loop stops. Sensors + `.jsonl` keep running. Logcat: `streaming disabled`. |
| **Toggle 📶 Streaming: ON** | Fresh connect; sending resumes. Logcat: `streaming enabled` → `connected to localhost:9500`. |
| **Stop the session** | `StreamClient.stop()` runs in `onDestroy` — no thread/socket leaks. |

Default is **ON** (`TrackingRepository.streamEnabled`), so streaming works as soon as the
server + tunnel are up. The toggle preference persists across sessions for the life of the
process.

## Watching Logcat

```bash
adb logcat -s StreamClient          # connect / reconnect / stop events
adb logcat -s StreamClient DataLogger SensorTrackingSvc
```

## Troubleshooting

- **No connection / immediate reconnect loop** — `adb reverse` not set (step 1), or no
  server listening on 9500. Check `adb reverse --list` and that the server is up.
- **Connected but nothing prints** — no session running on the watch (start Monitor All),
  or the sensor produced no valid samples yet.
- **Stops after unplugging / reconnecting the watch** — re-run `adb reverse`; the tunnel
  doesn't survive an adb reconnect even though the app's reconnect loop keeps trying.
- **`INTERNET` permission** — required even for localhost sockets; it's in the manifest.
```
