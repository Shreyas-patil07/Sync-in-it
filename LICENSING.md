# Licensing

Sync In It uses different licenses for different kinds of material. This file is the map. If anything here conflicts with a file header, the file header wins for that file.

## 1. Summary

| Material | Where | License | License file |
|---|---|---|---|
| Source code: C++ core, Android and iOS apps, Python simulator, tools, frontend source, build and CI scripts | `core/`, `android/`, `ios/`, `sim/`, `tools/`, `frontend/`, `.github/` | Apache License 2.0 | [`LICENSE`](LICENSE) |
| Test vectors and generated test data | `protocol/vectors/` | Apache License 2.0 | [`LICENSE`](LICENSE) |
| Documentation, specifications, diagrams, measurement reports | `docs/`, `docs_original/`, `protocol/*.md`, `README.md`, `frontend/README.md` | Creative Commons Attribution 4.0 International (CC BY 4.0) | [`LICENSE-DOCS`](LICENSE-DOCS) |
| Project name, logo and brand images | `docs/images/`, `frontend/public/images/` | **All rights reserved.** Not covered by the Apache-2.0 or CC BY 4.0 grants | none |
| Third-party software and fonts | dependencies | Their own licenses | [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) |

Attribution text required by Apache-2.0 is in [`NOTICE`](NOTICE).

## 2. Why these choices

- **Apache-2.0 for code.** It is permissive, it includes an explicit patent grant from each contributor, and it is compatible with the libraries this project plans to use (Oboe is Apache-2.0, Opus and GoogleTest are BSD-3-Clause). It requires keeping license and notice files and marking changes.
- **CC BY 4.0 for documentation.** It lets others reuse and adapt the documents with credit. Implementing the protocol described in the specification does not make an implementation a derivative of the specification text.
- **Not GPL.** A copyleft license would block reuse of permissively licensed libraries in some combinations and would make the project incompatible with some app-store and commercial uses. This can be revisited with an ADR.
- **Logo and name reserved.** Software and documentation licenses do not grant rights in names or logos, and Apache-2.0 section 6 says so explicitly for the code. The owner may later release the logo under CC BY or a brand policy.

## 3. Using this project

- **Code:** you may use, modify and distribute it, including commercially, if you keep the `LICENSE` and `NOTICE` files, mark files you changed, and keep copyright and attribution notices. There is no warranty.
- **Documentation:** you may share and adapt it with credit to "Sync In It" and a link to the repository, and an indication of any changes.
- **Logo and name:** please do not use them to suggest your product is this project or endorsed by it.

## 4. Contributing

- By submitting a contribution you agree it is licensed under the license that applies to the part of the repository you changed (inbound = outbound, as in Apache-2.0 section 5).
- You must have the right to submit it. Do not paste in code or text from sources you cannot relicense.
- Optional but recommended: sign commits off with `git commit -s` (Developer Certificate of Origin).

## 5. File headers (SPDX)

Add one line to each new source file:

| Language | Header |
|---|---|
| C / C++ / Kotlin / Swift / TypeScript | `// SPDX-License-Identifier: Apache-2.0` |
| Python / shell / YAML | `# SPDX-License-Identifier: Apache-2.0` |
| Markdown documents (optional) | `<!-- SPDX-License-Identifier: CC-BY-4.0 -->` |

## 6. Rules for third-party code and dependencies

1. Check the license **before** copying code or adding a dependency.
2. Allowed without discussion: MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC, BSL-1.0, zlib, and SIL OFL for fonts.
3. Needs an ADR first: LGPL, MPL, EPL, or any license with extra notice or relinking obligations.
4. Not allowed in this repository: GPL or AGPL code (it would force the combined work under the GPL), licenses with non-commercial or no-derivatives terms, and code with no license at all.
5. **Snapcast is GPL-licensed.** It is a design reference only. Do not copy its code, comments or file structure.
6. Record every new dependency in `THIRD_PARTY_NOTICES.md` in the same change that adds it.
7. Binary releases must show required notices to users (for example an "Open source licenses" screen in the app).

## 7. Patents

The documents cite published patents and papers as background. This project is an independent design. Citing a patent neither grants nor denies rights under it. The Apache-2.0 patent grant covers only patents held by contributors to this project. If you plan a commercial product, get advice from a patent attorney first.

## 8. Copyright holder

The copyright line in `NOTICE` currently uses the GitHub account name. Replace it with your legal name or organization if you prefer.

*This file explains the project's choices. It is not legal advice.*
