#!/usr/bin/env python3
"""
pulse_guard.py - Project Nox 24/7 Cloud Pulse & Anti-Duplication Watchdog Guard (Fail-Closed).

Coordinates the 5-minute recursive pulse and the 15-minute watchdog cron:
1. Mode detection: heartbeat (5-min self-trigger), watchdog (15-min safety cron), manual.
2. Watchdog liveness guard: if scheduled watchdog fires while a heartbeat run is
   already active/queued, watchdog immediately exits (WATCHDOG_ALREADY_ALIVE=true).
   If heartbeat chain is dead/absent, watchdog executes sync and restarts pulse (WATCHDOG_RESTART=true).
   FAIL-CLOSED: if query fails, watchdog refuses to restart (WATCHDOG_ACTIVE_STATE_UNKNOWN=true).
3. Pre-dispatch anti-split brain guard: queries GitHub API for other active runs before
   dispatching next pulse. If another run is queued/in_progress, skips dispatch (SKIPPED_ALREADY_ACTIVE).
   FAIL-CLOSED: if query fails, heartbeat refuses to dispatch in the dark (SKIPPED_ACTIVE_STATE_UNKNOWN).
4. Controlled retry backoff: retries dispatch up to 3 times (10s, 20s, 30s) instead of masking with `|| true`.
5. Structured pulse status logging with ACTIVE_RUN_CHECK, ACTIVE_RUN_CHECK_ATTEMPTS, ACTIVE_RUN_CHECK_ERROR.
"""

import argparse
from dataclasses import dataclass
import json
import os
import subprocess
import sys
import time
from typing import List, Optional


@dataclass
class ActiveRunsResult:
    status: str  # "OK" or "FAILED"
    runs: List[int]
    attempts: int
    error: Optional[str] = None

    @property
    def is_ok(self) -> bool:
        return self.status == "OK"

    @property
    def is_failed(self) -> bool:
        return self.status == "FAILED"


def set_github_output(key: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def log_pulse_status(
    pulse_mode: str,
    current_run_id: str,
    active_run_check: str,
    active_run_check_attempts: int,
    active_run_check_error: Optional[str],
    other_active_runs: str,
    next_pulse_required: bool,
    trigger_attempt: str,
    trigger_result: str,
    watchdog_restart: bool,
    watchdog_already_alive: Optional[bool] = None,
    watchdog_active_state_unknown: Optional[bool] = None,
) -> None:
    print("==================================================")
    print("PROJECT NOX — PULSE STATUS")
    print("==================================================")
    print(f"PULSE_MODE={pulse_mode}")
    print(f"CURRENT_RUN_ID={current_run_id}")
    print(f"ACTIVE_RUN_CHECK={active_run_check}")
    print(f"ACTIVE_RUN_CHECK_ATTEMPTS={active_run_check_attempts}")
    print(f"ACTIVE_RUN_CHECK_ERROR={active_run_check_error or 'NONE'}")
    print(f"OTHER_ACTIVE_RUNS={other_active_runs}")
    print(f"NEXT_PULSE_REQUIRED={'true' if next_pulse_required else 'false'}")
    print(f"NEXT_PULSE_TRIGGER_ATTEMPT={trigger_attempt}")
    print(f"NEXT_PULSE_TRIGGER_RESULT={trigger_result}")
    print(f"WATCHDOG_RESTART={'true' if watchdog_restart else 'false'}")
    if watchdog_already_alive is not None:
        print(f"WATCHDOG_ALREADY_ALIVE={'true' if watchdog_already_alive else 'false'}")
    if watchdog_active_state_unknown is not None:
        print(f"WATCHDOG_ACTIVE_STATE_UNKNOWN={'true' if watchdog_active_state_unknown else 'false'}")
    print("==================================================")
    sys.stdout.flush()


def get_active_runs(
    workflow_file: str,
    current_run_id: str,
    repo: Optional[str] = None,
    max_retries: int = 3,
    backoff_delays: Optional[List[int]] = None,
) -> ActiveRunsResult:
    """
    Queries gh run list to find active runs (queued or in_progress) of the given workflow,
    excluding current_run_id.
    FAIL-CLOSED: Returns status="FAILED" if all retries are exhausted. Never returns [] on error.
    """
    if backoff_delays is None:
        backoff_delays = [2, 4, 6]

    cmd = [
        "gh", "run", "list",
        "--workflow", workflow_file,
        "--json", "databaseId,status",
        "-L", "25",
    ]
    if repo:
        cmd.extend(["-R", repo])

    last_error = ""
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
                return ActiveRunsResult(
                    status="OK",
                    runs=active,
                    attempts=attempt,
                    error=None,
                )
            else:
                last_error = res.stderr.strip() or f"exit code {res.returncode}"
                print(
                    f"[Pulse Guard] Warning: 'gh run list' attempt {attempt}/{max_retries} failed: {last_error}",
                    file=sys.stderr,
                )
        except Exception as e:
            last_error = str(e)
            print(
                f"[Pulse Guard] Warning: Exception querying 'gh run list' (attempt {attempt}/{max_retries}): {e}",
                file=sys.stderr,
            )

        if attempt < max_retries:
            delay = backoff_delays[min(attempt - 1, len(backoff_delays) - 1)]
            time.sleep(delay)

    return ActiveRunsResult(
        status="FAILED",
        runs=[],
        attempts=max_retries,
        error=last_error or "Unknown failure querying gh run list",
    )


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
        set_github_output("watchdog_active_state_unknown", "false")
        set_github_output("active_run_check", "OK")
        print(f"[Pulse Guard] Mode is '{pulse_mode}'. Watchdog check passed; proceed to sync.")
        return 0

    # Watchdog mode: check if heartbeat is currently alive
    backoff = [0, 0, 0] if args.no_backoff else [2, 4, 6]
    result = get_active_runs(args.workflow, current_run_id, repo, backoff_delays=backoff)

    if result.is_failed:
        # FAIL-CLOSED: cannot confirm state, do NOT assume dead, do NOT restart
        print(
            f"[Pulse Guard] Watchdog active run query failed: {result.error}. "
            "Fail-closed: refusing to restart. Watchdog will retry on next 15-minute cron.",
            file=sys.stderr,
        )
        set_github_output("should_run_sync", "false")
        set_github_output("pulse_mode", "watchdog")
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "false")
        set_github_output("watchdog_active_state_unknown", "true")
        set_github_output("active_run_check", "FAILED")
        log_pulse_status(
            pulse_mode="watchdog",
            current_run_id=current_run_id,
            active_run_check="FAILED",
            active_run_check_attempts=result.attempts,
            active_run_check_error=result.error,
            other_active_runs="UNKNOWN",
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ACTIVE_STATE_UNKNOWN",
            watchdog_restart=False,
            watchdog_already_alive=False,
            watchdog_active_state_unknown=True,
        )
        return 1

    if result.runs:
        print(f"[Pulse Guard] Watchdog found active run(s): {result.runs}. Heartbeat is already alive.")
        set_github_output("should_run_sync", "false")
        set_github_output("pulse_mode", "watchdog")
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "true")
        set_github_output("watchdog_active_state_unknown", "false")
        set_github_output("active_run_check", "OK")
        log_pulse_status(
            pulse_mode="watchdog",
            current_run_id=current_run_id,
            active_run_check="OK",
            active_run_check_attempts=result.attempts,
            active_run_check_error=None,
            other_active_runs=str(len(result.runs)),
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=False,
            watchdog_already_alive=True,
            watchdog_active_state_unknown=False,
        )
        return 0

    print("[Pulse Guard] Watchdog detected heartbeat is dead (0 active runs). Restarting pulse!")
    set_github_output("should_run_sync", "true")
    set_github_output("pulse_mode", "watchdog")
    set_github_output("watchdog_restart", "true")
    set_github_output("watchdog_already_alive", "false")
    set_github_output("watchdog_active_state_unknown", "false")
    set_github_output("active_run_check", "OK")
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
    backoff = [0, 0, 0] if args.no_backoff else [2, 4, 6]

    # heartbeat and watchdog (when restarted) require scheduling the next pulse.
    # manual runs are one-off and do not schedule subsequent pulses.
    next_pulse_required = (pulse_mode in {"heartbeat", "watchdog"})

    if not next_pulse_required:
        check_result = get_active_runs(args.workflow, current_run_id, repo, backoff_delays=backoff)
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check=check_result.status,
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=check_result.error,
            other_active_runs=str(len(check_result.runs)) if check_result.is_ok else "UNKNOWN",
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

    # Pre-dispatch anti-split brain check (Fail-Closed)
    check_result = get_active_runs(args.workflow, current_run_id, repo, backoff_delays=backoff)

    if check_result.is_failed:
        # FAIL-CLOSED: cannot confirm state, do NOT dispatch in the dark
        print(
            f"[Pulse Guard] Error: Failed to check active runs ({check_result.error}). "
            "Fail-closed: skipping dispatch to avoid split-brain duplication. Watchdog will recover if needed.",
            file=sys.stderr,
        )
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check="FAILED",
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=check_result.error,
            other_active_runs="UNKNOWN",
            next_pulse_required=True,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ACTIVE_STATE_UNKNOWN",
            watchdog_restart=watchdog_restart,
            watchdog_already_alive=False,
            watchdog_active_state_unknown=True if is_watchdog else None,
        )
        return 1

    if check_result.runs:
        print(f"[Pulse Guard] Pre-dispatch check detected active runs: {check_result.runs}. Skipping next pulse.")
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check="OK",
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=None,
            other_active_runs=str(len(check_result.runs)),
            next_pulse_required=True,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=watchdog_restart,
            watchdog_already_alive=False,
        )
        return 0

    # check_result.is_ok and 0 active runs -> safe to dispatch
    dispatch_backoffs = [10, 20, 30] if not args.no_backoff else [0, 0, 0]
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
                active_run_check="OK",
                active_run_check_attempts=check_result.attempts,
                active_run_check_error=None,
                other_active_runs="0",
                next_pulse_required=True,
                trigger_attempt=str(attempt),
                trigger_result="DISPATCHED",
                watchdog_restart=watchdog_restart,
                watchdog_already_alive=False,
            )
            return 0
        else:
            print(f"[Pulse Guard] Dispatch attempt {attempt} failed: {res.stderr.strip()}", file=sys.stderr)
            if attempt < max_attempts:
                delay = dispatch_backoffs[attempt - 1]
                print(f"[Pulse Guard] Backing off for {delay}s before retry...")
                time.sleep(delay)

    # All dispatch attempts exhausted
    print("[Pulse Guard] All 3 dispatch attempts failed!", file=sys.stderr)
    log_pulse_status(
        pulse_mode=pulse_mode,
        current_run_id=current_run_id,
        active_run_check="OK",
        active_run_check_attempts=check_result.attempts,
        active_run_check_error=None,
        other_active_runs="0",
        next_pulse_required=True,
        trigger_attempt=str(max_attempts),
        trigger_result="FAILED",
        watchdog_restart=watchdog_restart,
        watchdog_already_alive=False,
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Project Nox Pulse Guard (Fail-Closed)")
    parser.add_argument("--workflow", default="sync_upstream.yml", help="Workflow filename")
    parser.add_argument("--ref", default="main", help="Git ref to dispatch")
    parser.add_argument("--repo", default=None, help="GitHub repo (e.g. owner/repo)")
    parser.add_argument("--event-name", default=None, help="GitHub event name override")
    parser.add_argument("--trigger-source", default=None, help="Trigger source override")
    parser.add_argument("--current-run-id", default=None, help="Current run ID override")
    parser.add_argument("--no-backoff", action="store_true", help="Disable retry backoff (for tests)")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # watchdog-check command
    subparsers.add_parser("watchdog-check", help="Check watchdog liveness before sync")

    # pulse-dispatch command
    dispatch_parser = subparsers.add_parser("pulse-dispatch", help="Sleep and dispatch next pulse")
    dispatch_parser.add_argument("--sleep-seconds", type=int, default=300, help="Seconds to wait before dispatch")

    args = parser.parse_args()

    if args.command == "watchdog-check":
        return cmd_watchdog_check(args)
    elif args.command == "pulse-dispatch":
        return cmd_pulse_dispatch(args)

    return 0


if __name__ == "__main__":
    sys.exit(main())
