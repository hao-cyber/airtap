import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("experience_review", Path(__file__).resolve().parents[1] / "experience_review.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def sample(**changes):
    return {"id": "case-one", "journey": "first_use", "ai": "muse", "version_code": 77,
            "last_setup_stage": "approved", "task_started": True, "result_verified": True,
            "task_seconds": 120, "manual_seconds": 90, "manual_verified": True,
            "required_confirmations": 1, "remedial_interventions": 0, **changes}


def summary(*rows):
    return m.summarize({"schema": "airtap-experience-v1", "synthetic": True, "attempts": list(rows)})


class ExperienceReviewTest(unittest.TestCase):
    def test_partial_and_failed_attempts_remain_in_denominator(self):
        result = summary(sample(), sample(id="case-failed", result_verified=False, task_seconds=None),
                         sample(id="case-assisted", remedial_interventions=1))
        self.assertEqual(result["verified_without_remedial_help"], {"count": 1, "of_task_attempts": 3})
        self.assertEqual(result["required_confirmations"], 3)
        self.assertEqual(result["unverified_or_incomplete_task_count"], 1)

    def test_copying_invitation_is_not_first_task_completion(self):
        result = summary(sample(last_setup_stage="invitation_sent", task_started=False, result_verified=False,
                                task_seconds=None, manual_seconds=None, manual_verified=False))
        self.assertEqual(result["setup_funnel"]["approved"], 0)
        self.assertEqual(result["first_verified_task_count"], 0)
        self.assertEqual(result["task_attempt_count"], 0)
        self.assertIsNone(result["median_ai_minus_manual_seconds"])

    def test_returning_use_not_in_first_use_funnel(self):
        result = summary(sample(journey="returning", last_setup_stage=None))
        self.assertEqual(result["first_use_count"], 0)
        self.assertEqual(result["matched_verified_comparisons"], 1)
        self.assertEqual(result["median_ai_minus_manual_seconds"], 30)

    def test_unverified_manual_comparison_does_not_count_as_savings(self):
        result = summary(sample(manual_verified=False))
        self.assertEqual(result["matched_verified_comparisons"], 0)

    def test_rejects_content_fields_duplicates_and_contradictions(self):
        invalid = [sample(phone_number="private"), sample(task_started=False), sample(last_setup_stage="installed"),
                   sample(task_seconds=float("nan")), sample(remedial_interventions=-1),
                   sample(manual_seconds=None), sample(required_confirmations=True), sample(task_seconds=0)]
        for row in invalid:
            with self.subTest(row=list(row)), self.assertRaises(ValueError):
                summary(row)
        with self.assertRaises(ValueError):
            summary(sample(), sample())

    def test_empty_sample_is_not_a_perfect_success_rate(self):
        result = summary()
        self.assertEqual(result["verified_without_remedial_help"], {"count": 0, "of_task_attempts": 0})
        self.assertIsNone(result["median_ai_minus_manual_seconds"])

    def test_dropouts_before_install_are_kept_with_unknown_version(self):
        result = summary(sample(version_code=None, last_setup_stage="arrived", task_started=False,
                                result_verified=False, task_seconds=None, manual_seconds=None, manual_verified=False))
        self.assertEqual(result["first_use_count"], 1)
        self.assertEqual(result["setup_funnel"]["installed"], 0)
        self.assertEqual(result["unknown_version_samples"], 1)
        self.assertEqual(result["observed_version_codes"], [])
