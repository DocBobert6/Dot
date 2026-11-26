# Dot Phase 0 — release verification (builder's own download)

Note (2026-11-26, slice 3): the APK copies were moved out of this
tree into ~/workspace/dot-verify/ — release APKs live on the
GitHub release, not in the repo. The hashes above still verify
against the release asset.

- Release: https://github.com/DocBobert6/Dot/releases/tag/v0.1.0-phase0.1
- Tag run: 45871075842 (build success, release success)
- Commit: 8aa45bfa84705bf4a9c019ea991260ac8e534112
- Downloaded: dot-v0.1.0-phase0.1.apk via browser_download_url,
  Accept: application/octet-stream (the API asset URL 302s to a
  presigned blob URL, which gh_api.py's NoRedirect refuses)

## Checks (all from this downloaded file, not poll output)

- `file`: Android package (APK), with gradle app-metadata.properties
- Size: 14,165,659 bytes — exactly the release asset size
- SHA-256: baab801a30e094532afc4a008dfbb5fc5fa8ed17b9791cefde0eae931489cdbe
- `apksigner verify --verbose --print-certs`: Verifies; v2 scheme
  true; 1 signer; DN CN=Dot, OU=Dot, O=Dot, C=US
- Signer cert SHA-256: 582202f3bc33df3263432002cba04d7d6c6fd6eb85af79c86f31efac71b8d00b
  — matches the keystore cert recorded in ~/workspace/dot-signing/README.md
  (58:22:02:F3:BC:33:DF:32:63:43:20:02:CB:A0:4D:7D:6C:6F:D6:EB:85:AF:79:C8:6F:31:EF:AC:71:B8:D0:0B)
- `aapt2 dump badging`: package app.dot, versionCode 1,
  versionName 0.1.0-phase0.1, label Dot,
  launchable-activity app.dot.MainActivity
- Permissions in the APK: VIBRATE, INTERNET, ACCESS_NETWORK_STATE
  (+ platform-added app.dot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)

## CI evidence (tag run build job log)

- :app:testDebugUnitTest BUILD SUCCESSFUL
- Dex guard passed: 116 app methods checked, max 111 registers
- assembleDebug + assembleRelease BUILD SUCCESSFUL
- Core .so + bridge .so symbol-verified in CI (libretro API set,
  Java_app_dot_Bridge_nativeApiVersion)

## First-run history (for the record)

- Run 45869552197 (first ever): failure — bridge CMake used `//`
  comments, invalid in CMake. Fixed, verified locally with cmake.
- Run 45870026077: failure — themes.xml used
  Theme.Material3.DayNight.NoActionBar, which lives in the Views
  Material library Dot does not depend on. Fixed to the platform
  parent + a Compose DotTheme.
- Run 45870564318 (main): SUCCESS.
- Run 45871075842 (tag v0.1.0-phase0.1): SUCCESS, release created.

## Billing hold

Public-repo builds run fine — the first run got a runner and built
the core within a minute. The hold blocks private repos only, as
expected. No hold failure seen in any of the four runs.
