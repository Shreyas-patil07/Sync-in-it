# Development Log — Sync In It

**Document status:** Active log  
**Created:** 1 November 2026  
**Purpose:** Record verified project changes, decisions and test evidence.

## 1. Logging rules

- Record completed work only after it exists in the repository or has been run and verified.
- Separate implementation progress from planning progress.
- Reference the relevant commit, pull request, file or test artifact where available.
- Do not mark a milestone complete because its document or checklist was written.
- Record failed tests and reverted experiments; do not silently remove unfavorable results.
- Keep detailed measured results in individual test reports and link them here.

## 2. Project baseline

### 1 November 2026 — Documentation restructuring started

**Type:** Documentation / planning  
**Status:** Completed for this documentation commit

- Inspected the existing repository documentation:
  - docs_original/Sync Engine.md
  - docs_original/System Structure.md
  - README.md
- Established `docs/` as the primary 10+1 documentation set based on the original design.
- Preserved the two original design documents in `docs_original/` as reference sources.
- No application code or test implementation was completed by this documentation activity.
- No M0–M9 milestone is marked complete.

### 2 November 2026 — Licensing files added

**Type:** Documentation / decision  
**Status:** Completed  
**Commit/PR:** [74b6b5b157faee051a737466dda2ed56b74b5f5a](https://github.com/Shreyas-patil07/Sync-in-it/commit/74b6b5b157faee051a737466dda2ed56b74b5f5a)  
**Summary:** Added `LICENSE` (Apache-2.0), `LICENSE-DOCS` (CC BY 4.0), `NOTICE`, `LICENSING.md` and `THIRD_PARTY_NOTICES.md`; updated the README license section and accepted ADR-017.  
**Verification:** All five root licensing files are present; README license references resolve; ADR-017 records the split-license decision.  
**Result:** Code, documentation, brand assets and third-party components have an explicit licensing map.  
**Risks or limitations:** `NOTICE` uses the GitHub account name as the copyright holder. Confirm the right to reserve the logo/name before relying on the brand-rights statement. Dependency license entries must be re-verified for exact versions at release. Not legal advice.  
**Next action:** Continue Dev-0 repository hygiene, then begin M0.

## 3. Current engineering status

| Area | Status | Evidence required to advance |
|---|---|---|
| Product requirements | Drafted | Review and approve capacity/device scope |
| Technical requirements | Drafted | Confirm device OS baselines and hardware inventory |
| Architecture | Drafted | Resolve open ADRs and review timing boundaries |
| App flow | Drafted | UX review and state-transition validation |
| UI design brief | Drafted | Visual design and usability review |
| Protocol/data schema | Draft proposal | Protocol review, test vectors and parser tests |
| Implementation plan | Drafted | M0 repository skeleton and task breakdown |
| Security | Draft baseline | Threat review and transport-hardening decision |
| Testing | Draft plan | Test instrumentation and repeatable setup |
| ADR register | Initial record | Review provisional decisions |

## 4. Milestone tracker

| Milestone | Description | Status |
|---|---|---|
| M0 | Python simulator for time sync and drift control | Planned |
| M1 | Time sync between two physical phones | Planned |
| M2 | Scheduled click-track start on two receivers | Planned |
| M3 | Output timestamp mapping and manual offsets | Planned |
| M4 | Drift controller and 30-minute run | Planned |
| M5 | 3–4 receivers, group deadline and reconnect | Planned |
| M6 | Opus and supported live capture | Planned |
| M7 | iOS receiver | Planned |
| M8 | Acoustic calibration | Planned |
| M9 | 6–10 phone target and train trial | Planned |

## 5. Open decisions

1. Resolve whether the device capacity means 10 phones total or 10 receivers plus the master.
2. Confirm the initial Android and iOS test devices.
3. Confirm whether a Mac and physical iPhone are available for M7.
4. Verify OS minimums against the required audio and background APIs.
5. Choose and record the final C++ unit-test framework.
6. Select the project license after checking whether any third-party code will be reused.
7. Decide when authenticated encryption becomes mandatory.

## 6. Test evidence index

No physical-device synchronization results are recorded in this initial log. Add one entry per test run using:
- Run ID.
- Date/time.
- App commit.
- Device and router configuration.
- Scenario and duration.
- Metrics and artifacts.
- Pass/fail decision.
- Limitations and follow-up issue.

## 7. Change-entry template

### YYYY-MM-DD — Short title

**Type:** Planning / Implementation / Test / Bug / Decision  
**Status:** Planned / In progress / Completed / Blocked / Reverted  
**Commit/PR:** Link or SHA  
**Summary:** What changed  
**Verification:** Tests and evidence  
**Result:** What was observed  
**Risks or limitations:** What remains uncertain  
**Next action:** Specific next step
