#!/usr/bin/env python3
"""Summarize manually reviewed, content-free experience samples. No network or device access."""
import argparse
import json
import re
import statistics
from pathlib import Path

STAGES = ("arrived", "installed", "permissions_ready", "invitation_sent", "approved")
FIELDS = {"id", "journey", "ai", "version_code", "last_setup_stage", "task_started",
          "result_verified", "task_seconds", "manual_seconds", "manual_verified",
          "required_confirmations", "remedial_interventions"}


def summarize(data):
    if (not isinstance(data, dict) or set(data) != {"schema", "synthetic", "attempts"}
            or data["schema"] != "airtap-experience-v1" or type(data["synthetic"]) is not bool
            or not isinstance(data["attempts"], list) or len(data["attempts"]) > 1000):
        raise ValueError("Expected a bounded airtap-experience-v1 sample")
    rows, ids = data["attempts"], set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != FIELDS:
            raise ValueError("Use only the documented content-free fields")
        if not isinstance(row["id"], str) or not re.fullmatch(r"case-[a-z0-9-]{1,32}", row["id"]) or row["id"] in ids:
            raise ValueError("Each sample needs a unique nonpersonal case ID")
        ids.add(row["id"])
        if row["journey"] not in ("first_use", "returning") or row["ai"] not in ("muse", "instinct", "other"):
            raise ValueError("Unknown journey or AI category")
        for field in ("required_confirmations", "remedial_interventions"):
            if type(row[field]) is not int or not 0 <= row[field] <= 100000:
                raise ValueError("Invalid count")
        if row["version_code"] is not None and (type(row["version_code"]) is not int or not 1 <= row["version_code"] <= 100000):
            raise ValueError("Record the actual tested version, or null if unobserved")
        for field in ("task_started", "result_verified", "manual_verified"):
            if type(row[field]) is not bool:
                raise ValueError("Outcome flags must be explicit booleans")
        for field in ("task_seconds", "manual_seconds"):
            value = row[field]
            if value is not None and (type(value) not in (int, float) or not 0 < value <= 604800):
                raise ValueError("Use positive measured seconds, or null for unknown")
        if row["journey"] == "first_use":
            if row["last_setup_stage"] not in STAGES:
                raise ValueError("Unknown setup stage")
            if row["task_started"] and row["last_setup_stage"] != "approved":
                raise ValueError("A first phone task requires an approved connection")
        elif row["last_setup_stage"] is not None:
            raise ValueError("Returning use does not repeat setup stages")
        if row["result_verified"] and not row["task_started"]:
            raise ValueError("A verified result requires an actual task")
        if row["task_seconds"] is not None and not row["task_started"]:
            raise ValueError("Task timing requires an actual task")
        if row["manual_verified"] and row["manual_seconds"] is None:
            raise ValueError("A matched manual comparison requires measured time")
    first = [r for r in rows if r["journey"] == "first_use"]
    tasks = [r for r in rows if r["task_started"]]
    verified = [r for r in tasks if r["result_verified"]]
    unaided = [r for r in verified if r["remedial_interventions"] == 0]
    matched = [r for r in verified if r["manual_verified"] and r["task_seconds"] is not None]
    return {
        "schema": "airtap-experience-summary-v1", "synthetic": data["synthetic"],
        "sample_count": len(rows), "first_use_count": len(first),
        "observed_version_codes": sorted({r["version_code"] for r in rows if r["version_code"] is not None}),
        "unknown_version_samples": sum(r["version_code"] is None for r in rows),
        "setup_funnel": {s: sum(STAGES.index(r["last_setup_stage"]) >= i for r in first) for i, s in enumerate(STAGES)},
        "first_verified_task_count": sum(r["result_verified"] for r in first),
        "task_attempt_count": len(tasks), "verified_task_count": len(verified),
        "unverified_or_incomplete_task_count": len(tasks) - len(verified),
        "verified_without_remedial_help": {"count": len(unaided), "of_task_attempts": len(tasks)},
        "required_confirmations": sum(r["required_confirmations"] for r in rows),
        "remedial_interventions": sum(r["remedial_interventions"] for r in rows),
        "matched_verified_comparisons": len(matched),
        "median_ai_minus_manual_seconds": statistics.median(r["task_seconds"] - r["manual_seconds"] for r in matched) if matched else None,
        "scope": "Descriptive sample only; no population success-rate estimate. Synthetic samples are not product evidence. Confirmations are separate from remedial assistance."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    args = parser.parse_args()
    try:
        if args.input.stat().st_size > 1024 * 1024:
            raise ValueError("Sample exceeds 1 MiB")
        print(json.dumps(summarize(json.loads(args.input.read_text())), ensure_ascii=False, indent=2))
    except (ValueError, OSError, TypeError, KeyError):
        parser.exit(2, "Invalid experience sample; no input content was printed. See docs/design/product-experience.md.\n")


if __name__ == "__main__":
    main()
