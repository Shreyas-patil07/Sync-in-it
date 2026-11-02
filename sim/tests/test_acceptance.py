# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from sync_in_it_sim.acceptance import evaluate_m0_acceptance
from sync_in_it_sim.scenarios import run_scenarios


class M0AcceptanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.results = run_scenarios(duration_s=60.0, seed=7)

    def test_reference_scenario_suite_passes_acceptance(self):
        report = evaluate_m0_acceptance(self.results)
        self.assertTrue(
            report["passed"],
            "\n".join(
                f"{item['name']}: observed={item['observed']}, expected={item['expected']}"
                for item in report["checks"]
                if not item["passed"]
            ),
        )
        self.assertGreaterEqual(report["check_count"], 50)
        self.assertEqual(report["failed_count"], 0)

    def test_nominal_offset_regression_fails_gate(self):
        results = copy.deepcopy(self.results)
        results["fast_clock_30ppm"]["offset_error_ms"] = 1.1
        report = evaluate_m0_acceptance(results)
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("fast_clock_30ppm.final_offset_error", failed)

    def test_asymmetric_bias_must_not_be_marked_converged(self):
        results = copy.deepcopy(self.results)
        results["asymmetric_delay"]["estimator_converged"] = True
        report = evaluate_m0_acceptance(results)
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("asymmetric_delay.bias_not_misreported_as_convergence", failed)

    def test_severe_asymmetry_must_trigger_hard_resync(self):
        results = copy.deepcopy(self.results)
        results["severe_asymmetric_delay"]["hard_resync_count"] = 0
        report = evaluate_m0_acceptance(results)
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("severe_asymmetric_delay.hard_resync_triggered", failed)

    def test_missing_scenario_fails_gate(self):
        results = copy.deepcopy(self.results)
        del results["burst_loss"]
        report = evaluate_m0_acceptance(results)
        failed = {item["name"] for item in report["checks"] if not item["passed"]}
        self.assertIn("burst_loss.present", failed)


if __name__ == "__main__":
    unittest.main()
