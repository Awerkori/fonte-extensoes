#!/usr/bin/env python3
"""
pulse_guard.py - Project Nox 24/7 Cloud Pulse & Anti-Duplication Watchdog Guard.

Coordinates the 5-minute recursive pulse and the 15-minute watchdog cron:
1. Mode detection: heartbeat (5-min self-trigger), watchdog (15-min safety cron), manual.
2. Watchdog liveness guard: if scheduled watchdog fires while a heartbeat run is
   already active/queued, watchdog immediately exits (WATCHDOG_ALREADY_ALIVE=true).
   If heartbeat chain is dead/absent, watchdog executes sync and restarts pulse (WATCHDOG_RESTART=true).
3. Pre-dispatch anti-split brain guard: queries GitHub API for other active runs before
   dispatching next pulse. If another run is queued/in_progress, skips dispatch (SKIPPED_ALREADY_ACTIVE).
4. Controlled retry backoff: retries dispatch up to 3 times (10s, 20s, 30s) instead of masking with `|| true`.
5. Structured pulse status logging.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from typing import List, Optional


def set_github_output(key: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def log_pulse_status(
    pulse_mode: str,
    current_run_id: str,
    other_active_runs: int,
    next_pulse_required: bool,
    trigger_attempt: str,
    trigger_result: str,
    watchdog_restart: bool,
    watchdog_already_alive: Optional[bool] = None,
) -> None:
    print("==================================================")
    print("PROJECT NOX — PULSE STATUS")
    print("==================================================")
    print(f"PULSE_MODE={pulse_mode}")
    print(f"CURRENT_RUN_ID={current_run_id}")
    print(f"OTHER_ACTIVE_RUNS={other_active_runs}")
    print(f"NEXT_PULSE_REQUIRED={'true' if next_pulse_required else 'false'}")
    print(f"NEXT_PULSE_TRIGGER_ATTEMPT={trigger_attempt}")
    print(f"NEXT_PULSE_TRIGGER_RESULT={trigger_result}")
    print(f"WATCHDOG_RESTART={'true' if watchdog_restart else 'false'}")
    if watchdog_already_alive is not None:
        print(f"WATCHDOG_ALREADY_ALIVE={'true' if watchdog_already_alive else 'false'}")
    print("==================================================")
    sys.stdout.flush()


def get_active_runs(
    workflow_file: str,
    current_run_id: str,
    repo: Optional[str] = None,
    max_retries: int = 3,
) -> List[int]:
    """
    Queries gh run list to find active runs (queued or in_progress) of the given workflow,
    excluding current_run_id.
    """
    cmd = [
        "gh", "run", "list",
        "--workflow", workflow_file,
        "--json", "databaseId,status",
        "-L", "25",
    ]
    if repo:
        cmd.extend(["-R", repo])

    for attempt in range(1, max_retries + 1):
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            if res.returncode == 0:
                data = json.loads(res.stdout or "[]")
                active = []
                for run in data:
                    run_id = str(run.get("databaseId"))
                    status = str(run.get("status", "")).lower()
                    if run_id != str(current_run_id) and status in {
                        "queued", "in_progress", "waiting", "requested", "pending"
                    }:
                        try:
                            active.append(int(run_id))
                        except ValueError:
                            pass
                return active
            else:
                print(
                    f"[Pulse Guard] Warning: 'gh run list' attempt {attempt} failed: {res.stderr.strip()}",
                    file=sys.stderr,
                )
        except Exception as e:
            print(
                f"[Pulse Guard] Warning: Exception querying 'gh run list' (attempt {attempt}): {e}",
                file=sys.stderr,
            )

        if attempt < max_retries:
            time.sleep(2)

    return []


def determine_pulse_mode(event_name: str, trigger_source: str) -> str:
    if event_name == "schedule":
        return "watchdog"
    src = (trigger_source or "").strip().lower()
    if src in {"heartbeat", "watchdog", "manual"}:
        return src
    return "manual"


def cmd_watchdog_check(args: argparse.Namespace) -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", args.event_name or "")
    trigger_source = os.environ.get("TRIGGER_SOURCE", args.trigger_source or "manual")
    current_run_id = os.environ.get(
        "CURRENT_RUN_ID", os.environ.get("GITHUB_RUN_ID", args.current_run_id or "0")
    )
    repo = os.environ.get("GITHUB_REPOSITORY", args.repo)

    pulse_mode = determine_pulse_mode(event_name, trigger_source)

    if pulse_mode != "watchdog":
        set_github_output("should_run_sync", "true")
        set_github_output("pulse_mode", pulse_mode)
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "false")
        print(f"[Pulse Guard] Mode is '{pulse_mode}'. Watchdog check passed; proceed to sync.")
        return 0

    # Watchdog mode: check if heartbeat is currently alive
    active_runs = get_active_runs(args.workflow, current_run_id, repo)
    if active_runs:
        print(f"[Pulse Guard] Watchdog found active run(s): {active_runs}. Heartbeat is already alive.")
        set_github_output("should_run_sync", "false")
        set_github_output("pulse_mode", "watchdog")
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "true")
        log_pulse_status(
            pulse_mode="watchdog",
            current_run_id=current_run_id,
            other_active_runs=len(active_runs),
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=False,
            watchdog_already_alive=True,
        )
        return 0

    print("[Pulse Guard] Watchdog detected heartbeat is dead (0 active runs). Restarting pulse!")
    set_github_output("should_run_sync", "true")
    set_github_output("pulse_mode", "watchdog")
    set_github_output("watchdog_restart", "true")
    set_github_output("watchdog_already_alive", "false")
    return 0


def cmd_pulse_dispatch(args: argparse.Namespace) -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", args.event_name or "")
    trigger_source = os.environ.get("TRIGGER_SOURCE", args.trigger_source or "manual")
    current_run_id = os.environ.get(
        "CURRENT_RUN_ID", os.environ.get("GITHUB_RUN_ID", args.current_run_id or "0")
    )
    repo = os.environ.get("GITHUB_REPOSITORY", args.repo)

    pulse_mode = determine_pulse_mode(event_name, trigger_source)
    is_watchdog = (pulse_mode == "watchdog")
    watchdog_restart = is_watchdog

    # heartbeat and watchdog (when restarted) require scheduling the next pulse.
    # manual runs are one-off and do not schedule subsequent pulses.
    next_pulse_required = (pulse_mode in {"heartbeat", "watchdog"})

    if not next_pulse_required:
        active_runs = get_active_runs(args.workflow, current_run_id, repo)
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            other_active_runs=len(active_runs),
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_NOT_REQUIRED",
            watchdog_restart=False,
        )
        return 0

    # Wait sleep interval before checking and dispatching
    if args.sleep_seconds > 0:
        print(f"[Pulse Guard] Waiting {args.sleep_seconds}s before checking and dispatching next pulse...")
        time.sleep(args.sleep_seconds)

    # Pre-dispatch anti-split brain check
    active_runs = get_active_runs(args.workflow, current_run_id, repo)
    if active_runs:
        print(f"[Pulse Guard] Pre-dispatch check detected active runs: {active_runs}. Skipping next pulse.")
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            other_active_runs=len(active_runs),
            next_pulse_required=True,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=watchdog_restart,
        )
        return 0

    # Dispatch with controlled retries
    backoffs = [10, 20, 30] if not args.no_backoff else [0, 0, 0]
    max_attempts = 3
    dispatch_cmd = [
        "gh", "workflow", "run", args.workflow,
        "--ref", args.ref,
        "-f", "trigger_source=heartbeat",
    ]
    if repo:
        dispatch_cmd.extend(["-R", repo])

    for attempt in range(1, max_attempts + 1):
        print(f"[Pulse Guard] Dispatching next pulse (attempt {attempt}/{max_attempts})...")
        res = subprocess.run(dispatch_cmd, capture_output=True, text=True, check=False)
        if res.returncode == 0:
            print(f"[Pulse Guard] Successfully dispatched next pulse on attempt {attempt}.")
            log_pulse_status(
                pulse_mode=pulse_mode,
                current_run_id=current_run_id,
                other_active_runs=0,
                next_pulse_required=True,
                trigger_attempt=str(attempt),
                trigger_result="DISPATCHED",
                watchdog_restart=watchdog_restart,
            )
            return 0
        else:
            print(f"[Pulse Guard] Dispatch attempt {attempt} failed: {res.stderr.strip()}", file=sys.stderr)
            if attempt < max_attempts:
                delay = backoffs[attempt - 1]
                print(f"[Pulse Guard] Backing off for {delay}s before retry...")
                time.sleep(delay)

    # All attempts exhausted
    print("[Pulse Guard] All 3 dispatch attempts failed!", file=sys.stderr)
    log_pulse_status(
        pulse_mode=pulse_mode,
        current_run_id=current_run_id,
        other_active_runs=0,
        next_pulse_required=True,
        trigger_attempt=str(max_attempts),
        trigger_result="FAILED",
        watchdog_restart=watchdog_restart,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Project Nox Pulse Guard")
    parser.add_argument("--workflow", default="sync_upstream.yml", help="Workflow filename")
    parser.add_argument("--ref", default="main", help="Git ref to dispatch")
    parser.add_argument("--repo", default=None, help="GitHub repo (e.g. owner/repo)")
    parser.add_argument("--event-name", default=None, help="GitHub event name override")
    parser.add_argument("--trigger-source", default=None, help="Trigger source override")
    parser.add_argument("--current-run-id", default=None, help="Current run ID override")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # watchdog-check command
    subparsers.add_parser("watchdog-check", help="Check watchdog liveness before sync")

    # pulse-dispatch command
    dispatch_parser = subparsers.add_parser("pulse-dispatch", help="Sleep and dispatch next pulse")
    dispatch_parser.add_argument("--sleep-seconds", type=int, default=300, help="Seconds to wait before dispatch")
    dispatch_parser.add_argument("--no-backoff", action="store_true", help="Disable retry backoff (for tests)")

    args = parser.parse_args()

    if args.command == "watchdog-check":
        return cmd_watchdog_check(args)
    elif args.command == "pulse-dispatch":
        return cmd_pulse_dispatch(args)

    return 0


if __name__ == "__main__":
    sys.exit(main())
