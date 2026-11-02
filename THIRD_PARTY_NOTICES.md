# Third-Party Notices

This file lists third-party software, fonts and reference material connected to Sync In It, with their licenses. Licenses were recorded from upstream at the time of writing. **Verify the license of the exact version before each release**, and update this file in the same change that adds or upgrades a dependency.

Last updated: 2 November 2026.

## 1. Referenced in the repository today (website)

| Component | Use | License | Notes |
|---|---|---|---|
| React, React DOM (18.x) | Website runtime | MIT | Bundled into the website build |
| lucide-react | Website icons | ISC | Some icons derive from Feather (MIT) |
| TypeScript | Website build | Apache-2.0 | Development dependency |
| Vite | Website build | MIT | Development dependency |
| @vitejs/plugin-react | Website build | MIT | Development dependency |
| @types/react, @types/react-dom | Type definitions | MIT | Development dependencies |
| Inter, Outfit, DM Mono | Website fonts | SIL Open Font License 1.1 | Loaded from Google Fonts at runtime, not stored in this repository |

## 2. Planned dependencies (not yet in the repository)

| Component | Planned use | License | Notes |
|---|---|---|---|
| Oboe (Google) | Android low-latency audio output and input | Apache-2.0 | Keep its license and NOTICE text |
| Opus (libopus) | Audio codec (milestone M6) | BSD-3-Clause | Include the notice in binary distributions |
| GoogleTest | C++ unit tests, if chosen (ADR-016) | BSD-3-Clause | Test-only |
| Catch2 | C++ unit tests, if chosen instead (ADR-016) | BSL-1.0 | Test-only |
| ZXing / zxing-android-embedded | QR generation and scanning on Android | Apache-2.0 | Preferred over proprietary scanning SDKs to keep the app fully open source |
| AndroidX libraries, Kotlin standard library, Kotlin coroutines | Android app | Apache-2.0 | |
| NumPy, SciPy | Python simulator | BSD-3-Clause | |
| Matplotlib | Simulator plots | Matplotlib license (BSD-style) | |
| pytest | Python tests | MIT | |

Android SDK, NDK and iOS SDK/Xcode are used as build tools under their own terms and are not redistributed here.

## 3. Reference only, no code incorporated

| Item | Relationship | License / status |
|---|---|---|
| Snapcast | Architectural inspiration (timestamped chunks, client time sync, sample slip) | GPL-3.0 (verify). **No Snapcast code may be copied into this repository** |
| RFC 7272 (IDMS) | Design model for group playout control | IETF document |
| Elson, Girod, Estrin, RBS (OSDI 2002) | Optional receiver-to-receiver sync idea | Published paper, cited only |
| Lazik et al., ultrasonic time sync on smartphones | Optional acoustic ideas | Published paper, cited only |
| Android CDD, Oboe headers | API behavior reference | Linked, not copied |
| US12401947, CN 122002178 A | Patents and applications cited as background | See LICENSING.md, section 7 |

## 4. Obligations when shipping binaries

1. Include the project `LICENSE` and `NOTICE`.
2. Reproduce the notices of bundled third-party components (for example Apache-2.0 NOTICE text, BSD copyright lines) in an "Open source licenses" screen or accompanying file.
3. For the website, generate a dependency license report from the lockfile before each deployment (a tool such as `license-checker` for npm) and confirm nothing outside the allowed list in LICENSING.md has appeared.
4. For the Python tools, generate the equivalent report with `pip-licenses`.

## 5. Adding a dependency

Add a row to the right table above, with component, use, license and any notes, in the same change that introduces it. If the license is not on the allowed list in LICENSING.md, open an ADR first.
