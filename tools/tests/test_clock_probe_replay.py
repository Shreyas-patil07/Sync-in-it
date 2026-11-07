# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("expected the clock replay executable path")
    executable = sys.argv[1]

    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        speaker_log = root / "speaker.txt"
        host_log = root / "host.txt"
        attempt = "replay-smoke"
        session_id = 77
        receiver_id = 3
        speaker_lines = [
            f"[DIAG {attempt}] WELCOME_ACCEPTED sessionId={session_id} receiverId={receiver_id} "
            "audioPort=47801 timePort=47802"
        ]
        host_lines = []
        base_ns = 100_000_000_000

        host_lags = (100_000, 500_000, 200_000, 800_000)
        speaker_lags = (100_000, 300_000, 200_000, 400_000)
        for index in range(40):
            sequence = index + 1
            t1 = base_ns + index * 1_000_000_000
            forward_delay = 5_800_000 if index % 4 == 0 else 6_400_000 + (index % 3) * 300_000
            host_lag = host_lags[(index // 4) % len(host_lags)]
            speaker_lag = speaker_lags[index % len(speaker_lags)]
            kernel_t2 = t1 + forward_delay
            app_t2 = kernel_t2 + host_lag
            t3 = app_t2 + 200_000
            kernel_t4 = t3 + forward_delay - 10_000_000
            app_t4 = kernel_t4 + speaker_lag

            host_lines.append(
                f"DIAG HOST_UDP_PROBE sessionId={session_id} sequence={sequence} "
                f"receiverId={receiver_id} appT2Ns={app_t2} kernelT2Ns={kernel_t2} "
                "kernelT2MappingUncertaintyNs=500"
            )
            speaker_lines.append(
                f"[DIAG {attempt}] CLOCK_PROBE sequence={sequence} t1Ns={t1} "
                f"t2Ns={app_t2} t3Ns={t3} t4Ns={app_t4} "
                f"roundTripNs={app_t4 - t1 - (t3 - app_t2)} rawOffsetNs=5000000 "
                f"kernelT4Ns={kernel_t4} kernelT4RealtimeNs={kernel_t4 + 1_700_000_000_000_000_000} "
                "kernelT4MappingUncertaintyNs=500"
            )

        speaker_log.write_text("\n".join(speaker_lines), encoding="utf-8")
        host_log.write_text("\n".join(host_lines), encoding="utf-8")
        completed = subprocess.run(
            [
                executable,
                "--speaker-log", str(speaker_log),
                "--host-log", str(host_log),
                "--attempt", attempt,
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        output = completed.stdout
        if "matchedKernelProbes=40" not in output:
            raise AssertionError(output)
        if "kernelReceiveFit residualRmsMs=0.000000" not in output:
            raise AssertionError(f"kernel replay did not recover the fixed offset:\n{output}")
        if "kernelReceiveFit" not in output or "stableStreak=3" not in output:
            raise AssertionError(f"kernel replay did not reproduce the readiness streak:\n{output}")
        application_fit = next(
            (line for line in output.splitlines() if line.startswith("applicationFit ")),
            "",
        )
        residual_text = next(
            (part.split("=", 1)[1] for part in application_fit.split() if part.startswith("residualRmsMs=")),
            "0",
        )
        if float(residual_text) <= 0.25:
            raise AssertionError(
                f"fixture must expose app capture delay above the unchanged 0.25 ms gate:\n{output}"
            )
        if "kernelReceiveHoldout count=" not in output:
            raise AssertionError(f"kernel holdout metric missing:\n{output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
