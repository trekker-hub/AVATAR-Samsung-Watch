# pull-data

Pull AVATAR session logs off the Galaxy Watch to this computer over Wi‑Fi (ADB).

The watch has no data USB port, so debugging/pulling happens over Wi‑Fi. Session
files are written on the watch at:

```
/sdcard/Android/data/com.example.galaxywatch5/files/AVATAR/avatar_<sessionId>.jsonl
```

## One-time setup on the watch

1. **Settings → About watch → Software** — tap *Software version* 7× to unlock **Developer options**.
2. **Settings → Developer options** — turn on **ADB debugging**.
3. **Settings → Developer options** — turn on **Debug over Wi‑Fi**. It shows an IP:port, e.g. `192.168.1.42:5555`.
4. Make sure the **watch and PC are on the same Wi‑Fi network**.

## Pull the data (easy — the window)

Double-click **`Pull Data.bat`**. A small window opens with three boxes:

1. **Watch IP address** — the IP the watch shows under *Debug over Wi‑Fi*
2. **Port** — usually `5555`
3. **Destination folder** — where the `.jsonl` files are saved (defaults to `avatar_logs/`)

Fill them in and click **Pull Data**. The IP is remembered for next time.

The first connection pops an **"Allow debugging?"** prompt on the watch face — tap **Allow**.

## Pull the data (command line, optional)

If you'd rather not use the window:

```powershell
.\pull.ps1 -Ip 192.168.1.42     # first run: use the IP the watch shows
.\pull.ps1                      # later runs: reuses the saved IP
```

Files land in `pull-data/avatar_logs/`. Both `avatar_logs/` and the saved `.watch` IP
are git-ignored.

## Load a session in pandas

```python
import pandas as pd
df = pd.read_json("avatar_logs/AVATAR/avatar_20260715_101500.jsonl", lines=True)
```

## Requirements

`adb` (Android platform-tools) must be installed. The script auto-detects it in
`%LOCALAPPDATA%\Android\Sdk\platform-tools\` if it isn't on your PATH.
