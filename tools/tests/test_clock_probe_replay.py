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
        welcome = (
            f"[DIAG {attempt}] WELCOME_ACCEPTED sessionId={session_id} receiverId={receiver_id} "
            "audioPort=47801 timePort=47802"
        )
        speaker_lines = [welcome]
        legacy_speaker_lines = [welcome]
        host_lines = []
        base_ns = 100_000_000_000

        host_lags = (100_000, 3_000_000, 100_000, 4_000_000)
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
                f"t2Ns={app_t2} t3Ns={t3} t4Ns={app_t4} appT4Ns={app_t4} "
                f"roundTripNs={app_t4 - t1 - (t3 - app_t2)} rawOffsetNs=5000000 "
                f"kernelT4Ns={kernel_t4} kernelT4RealtimeNs={kernel_t4 + 1_700_000_000_000_000_000} "
                "kernelT4MappingUncertaintyNs=500"
            )
            legacy_speaker_lines.append(
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
        def metric(label: str, key: str) -> float:
            line = next((line for line in output.splitlines() if line.startswith(label + " ")), "")
            value = next(
                (part.split("=", 1)[1] for part in line.split() if part.startswith(key + "=")),
                "0",
            )
            return float(value)

        application_residual = metric("applicationFit", "residualRmsMs")
        kernel_residual = metric("kernelReceiveFit", "residualRmsMs")
        if application_residual <= kernel_residual + 0.01:
            raise AssertionError(
                "kernel receive timestamps must improve the replay residual:\\n" + output
            )

        application_holdout_rms = metric("applicationHoldout", "rmsAbsOffsetErrorMs")
        kernel_holdout_rms = metric("kernelReceiveHoldout", "rmsAbsOffsetErrorMs")
        if kernel_holdout_rms >= application_holdout_rms:
            raise AssertionError(
                "kernel receive timestamps must improve future-offset holdout error:\\n" + output
            )

        # Keep replay compatibility with diagnostic exports produced before kernel
        # timestamps were fed into the live estimator.
        speaker_log.write_text("\\n".join(legacy_speaker_lines), encoding="utf-8")
        legacy_completed = subprocess.run(
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
        if "matchedKernelProbes=40" not in legacy_completed.stdout:
            raise AssertionError("legacy app-timestamp logs no longer replay:\\n" + legacy_completed.stdout)
        if "kernelReceiveFit residualRmsMs=0.000000" not in legacy_completed.stdout:
            raise AssertionError("legacy log kernel comparison failed:\\n" + legacy_completed.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
