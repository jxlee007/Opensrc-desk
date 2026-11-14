# Extended Desktop

Extended Desktop turns an Android 10+ (API 29+) phone into a wireless **Host** and an Android 5.0+ (API 21+) tablet (tested targeting Samsung Galaxy Tab 2, 1280×800 @ 160 dpi, 2.4 GHz Wi-Fi) into an ultra-low-latency **Extended Display** or **Live Host Screen Mirror**.

---

## Architecture Highlights

- **Service-Owned Session Survival (`ExtendedDesktopService` + `WorkstationRuntime`)**: Virtual displays, the persistent H.264 encoder `Surface`, and control/video TCP sockets live inside the foreground service runtime so closing or swiping `MainActivity` never drops a live desktop or mirror session.
- **v2 Security Handshake (`PairingCrypto` + `KeystoreCrypto`)**: ECDH ephemeral key exchange with 6-digit SAS verification on first pair, mutual HMAC-SHA256 challenge/response on reconnects, Android Keystore AES-GCM encrypted tokens, and anti-spoof rejection.
- **Hardware-First Streaming (`H264StreamEncoder` + `H264StreamDecoder`)**: Persistent Baseline Profile Level 3.1 H.264 encoder with unthrottled IDR keyframe bursts on mode transitions and automatic HW → SW decoder fallback (`OMX.google.h264.decoder` / `c2.android.avc.decoder`).
- **Dual Projection Modes**:
  - **Extended Desktop**: Independent `VirtualDisplay` hosting `DesktopShellActivity` and Shizuku-launched freeform apps on Display 1.
  - **Mirror Host Screen**: Hot-swaps a `MediaProjection` `VirtualDisplay` onto the live H.264 encoder `Surface` without dropping sockets or stalling the tablet decoder, with Android 14+ foreground-service escalation barrier and centered-fit Display 0 touch mapping.

---

## CHANGELOG

- **Phase 1 — Gate 1 passed**
  - Dual-role Host/Display runtime with mDNS + Wi-Fi Direct discovery, QR pairing, and v2 ECDH/SAS + mutual HMAC authentication.
  - Persistent `VirtualDisplay` + H.264 Baseline 3.1 encoder and 20-byte binary touch dispatcher tuned for 1280×800 Galaxy Tab 2.
  - `DesktopShellActivity` custom desktop launcher/taskbar and Shizuku freeform app launching (`am start --display <N> --windowingMode 5`).
- **Phase 2 — pending device test**
  - Searchable Host App List (`Search apps by name…` filter in Host Controller).
  - Explicit per-row dual actions: always-enabled **`Open on Display 1`** (`testTag("launch_app_<pkg>")`) and **`Move to Host`** with **`Best-effort`** subtitle (`enabled = app.isActiveOnVirtual`, `testTag("move_back_host_<pkg>")`).
  - Live `isActiveOnVirtual` tracking combining session-launched packages and real-time Shizuku `am stack list` (`displayId > 0`) polling.
- **Phase 3 — pending device test**
  - Always-visible **Extended Desktop | Mirror Host Screen** segmented switcher above the Host Controller subtabs (and above split panes on wide tablets).
  - Strict Android 14+ (`targetSdk = 36`) foreground service ordering barrier (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` completed via `CompletableDeferred` prior to `MediaProjectionManager.getMediaProjection()`).
  - Zero-disconnect hot-swap of the persistent H.264 encoder `Surface` plus immediate unthrottled IDR keyframe burst (`forceImmediate = true`).
  - Stored centered-fit normalized bounds (`MirrorContentBounds`) ignoring taps in letterbox/pillarbox margins and mapping active-region Display touches accurately to Host Display 0 via Shizuku.
  - Host-aspect-ratio Mirror fallback sizing toggle when OEM ROMs stretch `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`.
  - Explicit **View-only Mirror** warning banner and disabled Trackpad/IME controls when Shizuku is inactive; opt-in **Right-click sends Back (Mirror)** toggle (default OFF) when Shizuku is active.
  - Automatic revert to **Extended Desktop** (relaunching `DesktopShellActivity` and restoring tracked Display 1 apps) via `MediaProjection.Callback.onStop()`.

---

## Device Verification Checklists

### Gate 1 — Core Extended Desktop & Pairing *(Passed)*
- [x] Initial ECDH + 6-digit SAS pairing and silent mutual HMAC reconnect between Host (Android 10/12/15) and Galaxy Tab 2.
- [x] Service-owned session survival when swiping `MainActivity` from Recents.
- [x] `DesktopShellActivity` renders at 1280×800 without decoder stall (`OMX` / SW fallback).
- [x] Shizuku touch injection lands strictly on Display 1 without leaking to Display 0.

### Gate 2 — Searchable App List & Dual Row Actions *(Pending Device Test)*
- [ ] Host Controller **Apps** tab renders `Search apps by name…` and filters `launchableApps` cleanly by label/package.
- [ ] Every row displays **`Open on Display 1`** (Cyan, always enabled) and **`Move to Host`** (`Best-effort`, disabled until app is active on Display 1).
- [ ] Launching an app from Host Controller or Display 1 Taskbar enables its **`Move to Host`** button and marks it *Running on Display 1*.
- [ ] External/Display 1 task closures or Taskbar switches sync via `am stack list` (`displayId > 0`) within ~3 seconds.
- [ ] Tapping **`Move to Host`** executes `am start --display 0` without `force-stop` and updates status back to Host.

### Gate 3 — Live MediaProjection Host Screen Mirroring *(Pending Device Test)*
- [ ] Switching to **Mirror Host Screen** opens Android's screen-capture consent dialog; canceling keeps **Extended Desktop** active with zero FGS escalation or crash.
- [ ] Confirming **Entire screen** escalates `ExtendedDesktopService` to `connectedDevice|mediaProjection` **before** calling `getMediaProjection()` on Android 14/15 with no `SecurityException`.
- [ ] Verify aspect ratio on Galaxy Tab 2 (1280×800): confirm Display 0 is not distorted; if an OEM ROM stretches `AUTO_MIRROR`, enable **Host-aspect Mirror fallback** and confirm clean aspect fit.
- [ ] Verify Display 0 touch mapping: taps inside black letterbox/pillarbox bars are ignored; taps inside the active mirrored frame hit the exact Display 0 UI coordinates when Shizuku is authorized.
- [ ] Verify view-only safety: with Shizuku inactive, **Mirror Host Screen** displays the amber **View-only Mirror** banner and disables Trackpad & IME controls.
- [ ] Verify **Right-click sends Back (Mirror)** defaults OFF and sends `KEYCODE_BACK` to Display 0 only when enabled.
- [ ] Stopping capture from the Android system bar/pill triggers `MediaProjection.Callback.onStop()`, downgrades FGS type, restores **Extended Desktop** + `DesktopShellActivity`, and keeps the video stream connected without re-pairing.
