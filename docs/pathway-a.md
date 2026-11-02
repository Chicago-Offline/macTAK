# Pathway A Baseline: WinTAK-in-VM Reference (Golden Image)

Pathway A is not a port path — it is the **fidelity oracle** for Pathway C: a running WinTAK (the only shipped desktop TAK client) whose UX and behavior the macOS port's parity rungs (`roadmap.md` G6) and parity QA (`ci-qa.md` §6.4) are checked against. `pathway-c.md` §6 names the gap: with no macOS-native or Linux TAK client in existence, WinTAK is the only reference UX. The VM also instantiates the self-hosted CI pattern of `ci-qa.md` §5.

**Status: built and smoke-tested 2026-11-01.** Golden snapshot **`golden-wintak`** (id `4B9A0FC3-FCCB-48EC-87BB-BFA368B51B81`, 10.1 GB, includes the running session). Extended 2026-11-02 with a **verified live FreeTAKServer CoT round-trip**, stacked as a second snapshot **`golden-wintak-fts`** (§FTS round-trip) — the plain golden below it stays pristine.

## The golden VM (verified)

| Property | Value |
|---|---|
| VM | `wintak-baseline` (VMPal), id `8F1A4374-F6BC-4972-9306-9DC3DE92F763` |
| Guest OS | Windows 11 Pro 24H2 ARM64, build 26100.4349 (unattended install, ~7 min) |
| App | **WinTAK-CIV 5.8.0.149** — silent MSI install, verified via verbose log + registry |
| Hardware | 9 vCPU · 16 GB · 512 GB (thin) · GPU acceleration **on** |
| Direct3D 12 | **off — golden-image invariant** (§Gotchas #1) |
| Network | Bridged (a LAN member of its own) |
| Sign-in | Automatic at every start; VMPal keeps the account password (never leaves this Mac, never enters this repo) |
| Repo hygiene | The ~344 MB of installers live in `WinTAK-CIV_5/` on the Mac, excluded by `.gitignore` |

## Reproduce (the whole procedure)

1. VMPal New VM → Windows 11 24H2; 9 CPU / 16 GB / 512 GB; GPU acceleration on; auto sign-in (unattended, ~7 min).
2. Copy the installers into the guest.
3. Elevated silent install — **the MSI alone suffices** (the bootstrapper EXE is not needed):
   ```
   msiexec /i WinTAK-5.8.0.149-civ-x64.msi /qn /norestart /l*v wintak-install.log
   ```
   Verbose log at `C:\Users\<user>\wintak-install.log` ends "Installation completed successfully"; reboot suppressed.
4. Launch, click through the first-run Device Setup Wizard (3 steps; data-package import and callsign skippable) and the imagery/WMS dialog — defaults accept the NRL + USGS imagery sources.
5. Snapshot. The golden was taken **with WinTAK running**, so a resumed clone lands directly on the main map; take it from VMPal's VM menu.

## Smoke test (what "working" means here)

Measured on the built image, 2026-11-01:

- Bootstrapper completes in **19.6 s** ("Bootstrapper sequence completed" in the app log); process stable past 168 s with ribbon + map fully rendered; working set **1.4–1.6 GB**.
- CoT listeners bound on **TCP 4242 and 8087**.
- First-run state settled: wizard completed, no blocking dialogs.
- Evidence paths in the guest: app log `%APPDATA%\WinTAK\Logs\Log-WinTAK-<date>.txt`; the MSI verbose log of step 3.

## Gotchas (each cost a debugging session — do not rediscover)

1. **Direct3D 12 kills WinTAK in this VM.** With D3D12 on, ANGLE/EGL selects it and WinTAK exits silently mid-startup ("Initializing action bar"; no exception, no WER event, no dump). Fix, and invariant: VM setting Direct3D 12 **off** (takes effect at the next full shutdown + start). If WinTAK dies silently after a settings change, check this first.
2. **Never launch WinTAK from an agent exec job.** VMPal `exec` (any job-object-parented shell) kills its process tree when the job closes — WinTAK launched via `Start-Process` died at ~25–60 s, looking exactly like a crash. Launch long-lived GUI apps so they parent outside the job, e.g. WMI:
   ```powershell
   Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '"C:\Program Files\WinTAK\WinTAK.exe"' }
   ```
3. Guest tooling quirks: prefer PowerShell over `cmd` (quoting of spaced paths unreliable); a bare `cmd.exe` as the exec command errors — give the full path and `/c`; `--admin` execs with cmd need `/c` or an interactive shell opens.
4. **WinTAK's UI thread hangs after long automated sessions.** Mid-session the app stops accepting input (all clicks/keys ignored) with occasional "WinTAK encountered an Unhandled Exception" toasts, while the process still reports `Responding=True`. Recovery: kill + WMI relaunch (gotcha #2) — ~20 s to a fresh, auto-connected instance. Budget a restart before any scripted UI pass.
5. **Blind UI automation of the map is unreliable.** GoTo coordinates, manual location, and wheel zoom each fought scripted interaction at this window scale (guest display 5120×2600 mapped into a 1280×650 coordinate space; UI text ~4 px, hard for OCR too). Deterministic alternatives: connections via `Config.xml` (§FTS round-trip); marker placement via `../reference/tools/inject-marker.py` with receipt verified in FTS's log or a listener, not on screen.
6. **The `vigsecdrone/freetakserver` image swaps the classic FTS port roles:** plaintext CoT = **8087**, SSL CoT = **8089** — plain TCP into 8089 dies with `SSL: WRONG_VERSION_NUMBER` (exactly what the Connection Manager's 8089 default pairing hits; WinTAK's own TCP default of 8088 is closer but also wrong here). FTS's REST API (19020) did not answer on this build; verify at the CoT layer with `../reference/tools/cot-listen.py`. FTS 2.x also keeps zombie client sockets in its client dict — restart the container between test rounds, and note it only registers a client after that client's first message (a silent socket never counts).

## CI use (ties to ci-qa.md §5)

Each self-hosted job clones the golden and runs in the clone:

```sh
vmpal run wintak-baseline --copy-in scenario:src --copy-out out:./out -- \
  powershell.exe -Command '<capture / parity script>'
```

Verified against `vmpal help run` (2026-11-01): the clone is copy-on-write, is signed in with VMPal's kept password, runs the command, copies artifacts out (also on failure), and is deleted for good; exit status = job status. Policy unchanged from `ci-qa.md` §3/§5: WinTAK never on hosted CI, never committed, never cached; clones get fresh network identities.

## FTS round-trip (done 2026-11-02)

Live WinTAK ↔ FreeTAKServer CoT exchange, built on the golden without disturbing it (stacked snapshot on top; see gotcha #6 for the port-role trap).

**Stack.** FTS runs **Mac-side**, not in the VM: VMPal Containers (Linux kernel 7.3.0, 4 GB) running the community image `vigsecdrone/freetakserver:latest` (arm64 + amd64; container name `freetakserver`, `network: host`). WinTAK in the bridged VM connects over the LAN like any ordinary client.

**Connectivity.** VMPal publishes container ports on the Mac's **localhost only** — `lsof` shows `127.0.0.1` bindings even with `network: host` — so the bridged VM cannot reach them directly. Bridge: `../reference/tools/cot-proxy.py` listens on the Mac's LAN address and forwards into the localhost forwarder, one instance per port (start with `nohup`; logs to `/tmp/cot-proxy.log`):

```sh
python3 cot-proxy.py <mac-lan-ip> 8087 8087   # CoT TCP for WinTAK
python3 cot-proxy.py <mac-lan-ip> 8089 8089   # CoT SSL, if ever needed
```

**WinTAK side.** The Connection Manager UI works but automation is fragile (gotcha #5); the deterministic path is the config file. Kill WinTAK first — it rewrites the file on exit — then edit `%APPDATA%\WinTAK\Config.xml` and relaunch via WMI:

```xml
<CursorOnTarget>
  ...
  <Streams>
    <Stream desc="FreeTAKServer" uri="tcp://<mac-lan-ip>:8087" useLdapAuth="False" enabled="True" enrollForCertificateWithTrust="False" />
  </Streams>
</CursorOnTarget>
```

WinTAK auto-connects at launch (toast: "Connected to tcp://…:8087") and sends presence continuously (callsign from the device setup, type `a-f-G-U-C-I`).

**Verification (protocol-level, third-party witnessed).** Two Mac-side CoT clients, `../reference/tools/cot-listen.py` (passive listener that sends one handshake event so FTS registers it) and `../reference/tools/inject-marker.py` (sends a CoT marker and holds the socket):

- WinTAK's own presence (`callsign=HURRICANE`, `takv version="5.8.0.149" platform="WinTAK-CIV"`) arrived at the listener through FTS — **WinTAK → FTS → other client** ✔
- A marker injected from the Mac came back to the listener — **FTS → clients** ✔ — and FTS's stdout shows `sent to client S-1-5-21-…` for WinTAK's client — **FTS → WinTAK** ✔
- Evidence excerpt: `../reference/captures/fts-roundtrip-evidence.txt` (local only, like all WinTAK artifacts).

**Stacked snapshot:** `golden-wintak-fts` (id `2588E027-5008-4289-8249-385638E9E80F`, taken 2026-11-02 with WinTAK running, session included, FTS connection saved in Config.xml). The FTS container is Mac-side state and is **not** part of the snapshot: revive the stack with `vmpal_run_container` (`vigsecdrone/freetakserver:latest`, `network: host`) + the two proxy instances, then any resumed clone reconnects on its own.

**Reference capture (G6).** `../reference/captures/wintak-ref-1-map.png` — full guest display (5120×2600), main map + ribbon, captured from inside the guest via `../reference/tools/capture.ps1` (PowerShell `CopyFromScreen` → `Z:\Downloads`, which maps to the Mac's Downloads folder). The full parity set (device setup wizard, tool surfaces, map interactions) is still to be captured **from clones** per the policy below; note captures and evidence live in `../reference/captures/`, which is `.gitignore`d (GOTS-derived artifacts never enter the public repo).

## Open

- ~~FTS round-trip~~ — **done 2026-11-02**, stacked snapshot `golden-wintak-fts` (§FTS round-trip).
- Reference capture for the G6 parity checklists: one full-display map capture exists (taken on top of the golden before snapshotting, so `golden-wintak` itself remains pristine); the full set (device setup, tool surfaces, map interactions) still to be captured from **clones**, never from snapshot-generation runs.