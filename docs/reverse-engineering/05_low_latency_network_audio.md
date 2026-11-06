# Low-Latency Audio Networking — Product Briefs

**Catalogue IDs:** 29–31 · **Research date:** 7 November 2026

## 29 — SonoBus

- **Workflow:** Peer-to-peer low-latency live audio networking for musicians and supported DAW/plugin workflows.
- **Synchronization boundary:** Live bidirectional audio with configurable jitter buffers; objective is interactive network performance, not necessarily aligning a fixed pre-recorded song across room endpoints.
- **Strengths:** Open-source implementation and guide; relevant for jitter-buffer, codec and live-network fault handling.
- **Unknowns:** Behaviour over particular Wi-Fi/mobile routes, exact device audio latency, and settings under Android output routes.
- **REA focus:** Inspect source and guide; test latency/jitter under varied network conditions; distinguish round-trip latency from endpoint acoustic skew.
- **Sources:** [GitHub](https://github.com/sonosaurus/sonobus), [user guide](https://github.com/sonosaurus/sonobus/blob/main/doc/SonoBus%20User%20Guide.md)

## 30 — Jamulus

- **Workflow:** Online musical collaboration across desktop and mobile clients.
- **Synchronization boundary:** Client/server low-latency audio mixing and jitter buffers; intended for live performance where round-trip latency matters.
- **Strengths:** Public manuals/FAQ and mature discussion of latency and network-quality constraints.
- **Unknowns:** Whether any technique transfers directly to multi-room pre-recorded playback; those objectives differ.
- **REA focus:** Study packet buffering, network quality metrics and failure scenarios. Do not use low round-trip latency as a substitute for acoustic multi-speaker sync measurement.
- **Sources:** [FAQ](https://jamulus.app/wiki/FAQ), [software manual](https://jamulus.app/wiki/Software-Manual)

## 31 — Airfoil / Airfoil Satellite

- **Workflow:** Desktop audio routing to remote endpoints, with companion receiver software.
- **Synchronization boundary:** Documentation describes compensating for different output transport delays by delaying local playback to the slowest endpoint. This is output-delay compensation, not necessarily a low-latency engine.
- **Strengths:** Useful product-level reference for endpoint delay alignment and output-route differences.
- **Unknowns:** Full protocol details and performance across all downstream receiver/audio routes.
- **REA focus:** Measure per-output transport delay, local-vs-remote compensation, Bluetooth route effect and whether calibration persists.
- **Sources:** [Manual](https://www.rogueamoeba.com/support/manuals/airfoil/), [latency knowledge base](https://rogueamoeba.com/support/knowledgebase/?showArticle=Airfoil-AudioLatency), [supported outputs](https://www.rogueamoeba.com/support/manuals/airfoil/?page=supportedoutputs)

## Cross-entry takeaway

SonoBus and Jamulus are useful references for live network audio and jitter tolerance, but their round-trip latency goal is not the same as synchronized playout. Airfoil is more relevant to output-route delay compensation. Sync In It must measure network clock offset, digital playout and acoustic output separately.
