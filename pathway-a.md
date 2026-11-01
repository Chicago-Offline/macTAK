# Pathway A Baseline: WinTAK-in-VM Reference (Golden Image)

Pathway A is not a port path — it is the **fidelity oracle** for Pathway C: a running WinTAK (the only shipped desktop TAK client) whose UX and behavior the macOS port's parity rungs (`roadmap.md` G6) and parity QA (`ci-qa.md` §6.4) are checked against. `pathway-c.md` §6 names the gap: with no macOS-native or Linux TAK client in existence, WinTAK is the only reference UX. The VM also instantiates the self-hosted CI pattern of `ci-qa.md` §5.

**Status: built and smoke-tested 2026-11-01.** Golden snapshot **`golden-wintak`** (id `4B9A0FC3-FCCB-48EC-87BB-BFA368B51B81`, 10.1 GB, includes the running session). Deferred: live FreeTAKServer CoT round-trip — snapshots stack, so revert to golden, extend, and re-snapshot later.

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

## CI use (ties to ci-qa.md §5)

Each self-hosted job clones the golden and runs in the clone:

```sh
vmpal run wintak-baseline --copy-in scenario:src --copy-out out:./out -- \
  powershell.exe -Command '<capture / parity script>'
```

Verified against `vmpal help run` (2026-11-01): the clone is copy-on-write, is signed in with VMPal's kept password, runs the command, copies artifacts out (also on failure), and is deleted for good; exit status = job status. Policy unchanged from `ci-qa.md` §3/§5: WinTAK never on hosted CI, never committed, never cached; clones get fresh network identities.

## Open

- **FTS round-trip** (deferred): revert to golden → VMPal Containers + FreeTAKServer → CoT round-trip WinTAK↔FTS → capture as a second, stacked snapshot.
- Reference capture for the G6 parity checklists (device setup, map interactions, tool surfaces) — screenshot/video from clones of this golden, never the golden itself.