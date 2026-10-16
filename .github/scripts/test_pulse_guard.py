#!/usr/bin/env python3
"""
Unit tests for pulse_guard.py (Fail-Closed Active Run Guard).
"""

from dataclasses import dataclass
import io
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pulse_guard
from pulse_guard import ActiveRunsResult


class TestPulseGuard(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.output_file = os.path.join(self.temp_dir.name, "github_output")
        os.environ["GITHUB_OUTPUT"] = self.output_file

    def tearDown(self):
        self.temp_dir.cleanup()
        os.environ.pop("GITHUB_OUTPUT", None)
        os.environ.pop("GITHUB_EVENT_NAME", None)
        os.environ.pop("TRIGGER_SOURCE", None)
        os.environ.pop("CURRENT_RUN_ID", None)
        os.environ.pop("GITHUB_RUN_ID", None)

    def read_github_output(self):
        if not os.path.exists(self.output_file):
            return {}
        result = {}
        with open(self.output_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line:
                    k, v = line.split("=", 1)
                    result[k] = v
        return result

    def test_determine_pulse_mode(self):
        self.assertEqual(pulse_guard.determine_pulse_mode("schedule", ""), "watchdog")
        self.assertEqual(pulse_guard.determine_pulse_mode("workflow_dispatch", "heartbeat"), "heartbeat")
        self.assertEqual(pulse_guard.determine_pulse_mode("workflow_dispatch", "watchdog"), "watchdog")
        self.assertEqual(pulse_guard.determine_pulse_mode("workflow_dispatch", "manual"), "manual")
        self.assertEqual(pulse_guard.determine_pulse_mode("workflow_dispatch", ""), "manual")

    @patch("subprocess.run")
    def test_get_active_runs_filters_current_and_completed(self, mock_run):
        mock_run.return_value = MagicMock(
            returncode=0,
            stdout=json.dumps([
                {"databaseId": 100, "status": "completed"},
                {"databaseId": 101, "status": "in_progress"},
                {"databaseId": 102, "status": "queued"},
                {"databaseId": 999, "status": "in_progress"},  # current run
            ])
        )
        res = pulse_guard.get_active_runs("sync_upstream.yml", current_run_id="999", backoff_delays=[0, 0, 0])
        self.assertEqual(res.status, "OK")
        self.assertEqual(res.runs, [101, 102])
        self.assertEqual(res.attempts, 1)

    # =========================================================================
    # CENÁRIO A: gh run list responde com [] -> ACTIVE_RUN_CHECK=OK -> DISPATCHED
    # =========================================================================
    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_scenario_a_heartbeat_dispatches_when_zero_runs(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        mock_run.return_value = MagicMock(returncode=0)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("ACTIVE_RUN_CHECK=OK", stdout)
        self.assertIn("OTHER_ACTIVE_RUNS=0", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DISPATCHED", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=1", stdout)

    # =========================================================================
    # CENÁRIO B: gh run list retorna run ativa -> SKIPPED_ALREADY_ACTIVE
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_scenario_b_heartbeat_skipped_when_active_run_exists(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[200], attempts=1, error=None)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("ACTIVE_RUN_CHECK=OK", stdout)
        self.assertIn("OTHER_ACTIVE_RUNS=1", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ALREADY_ACTIVE", stdout)

    # =========================================================================
    # CENÁRIO C: gh run list falha 3x -> ACTIVE_RUN_CHECK=FAILED -> NÃO despacha
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_scenario_c_heartbeat_fail_closed_on_query_failure(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = ActiveRunsResult(
            status="FAILED", runs=[], attempts=3, error="API rate limit exceeded"
        )
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            with patch("sys.stderr", new_callable=io.StringIO):
                ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 1)
        self.assertIn("ACTIVE_RUN_CHECK=FAILED", stdout)
        self.assertIn("ACTIVE_RUN_CHECK_ATTEMPTS=3", stdout)
        self.assertIn("ACTIVE_RUN_CHECK_ERROR=API rate limit exceeded", stdout)
        self.assertIn("OTHER_ACTIVE_RUNS=UNKNOWN", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ACTIVE_STATE_UNKNOWN", stdout)

    # =========================================================================
    # CENÁRIO D: watchdog + consulta falha -> NÃO reinicia cadeia
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_scenario_d_watchdog_fail_closed_on_query_failure(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        mock_active.return_value = ActiveRunsResult(
            status="FAILED", runs=[], attempts=3, error="GitHub API 503 Service Unavailable"
        )
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", no_backoff=True
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            with patch("sys.stderr", new_callable=io.StringIO):
                ret = pulse_guard.cmd_watchdog_check(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 1)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "false")
        self.assertEqual(outputs.get("watchdog_restart"), "false")
        self.assertEqual(outputs.get("watchdog_already_alive"), "false")
        self.assertEqual(outputs.get("watchdog_active_state_unknown"), "true")
        self.assertEqual(outputs.get("active_run_check"), "FAILED")
        self.assertIn("ACTIVE_RUN_CHECK=FAILED", stdout)
        self.assertIn("WATCHDOG_RESTART=false", stdout)
        self.assertIn("WATCHDOG_ACTIVE_STATE_UNKNOWN=true", stdout)
        self.assertIn("OTHER_ACTIVE_RUNS=UNKNOWN", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ACTIVE_STATE_UNKNOWN", stdout)

    # =========================================================================
    # CENÁRIO E: watchdog + consulta OK + zero runs -> WATCHDOG_RESTART=true
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_scenario_e_watchdog_restarts_when_zero_runs(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", no_backoff=True
        )

        ret = pulse_guard.cmd_watchdog_check(args)
        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "true")
        self.assertEqual(outputs.get("pulse_mode"), "watchdog")
        self.assertEqual(outputs.get("watchdog_restart"), "true")
        self.assertEqual(outputs.get("watchdog_already_alive"), "false")
        self.assertEqual(outputs.get("watchdog_active_state_unknown"), "false")
        self.assertEqual(outputs.get("active_run_check"), "OK")

    # =========================================================================
    # CENÁRIO F: watchdog + consulta OK + run ativa -> WATCHDOG_ALREADY_ALIVE=true
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_scenario_f_watchdog_skips_when_active_run_exists(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[101], attempts=1, error=None)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", no_backoff=True
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_watchdog_check(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "false")
        self.assertEqual(outputs.get("pulse_mode"), "watchdog")
        self.assertEqual(outputs.get("watchdog_restart"), "false")
        self.assertEqual(outputs.get("watchdog_already_alive"), "true")
        self.assertEqual(outputs.get("watchdog_active_state_unknown"), "false")
        self.assertEqual(outputs.get("active_run_check"), "OK")
        self.assertIn("WATCHDOG_ALREADY_ALIVE=true", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ALREADY_ACTIVE", stdout)

    # =========================================================================
    # CENÁRIO G: falha em 2 tentativas, sucesso na 3ª -> status OK, attempts=3
    # =========================================================================
    @patch("subprocess.run")
    def test_scenario_g_retry_succeeds_on_third_attempt(self, mock_run):
        mock_run.side_effect = [
            MagicMock(returncode=1, stderr="Temporary connection reset"),
            MagicMock(returncode=1, stderr="Rate limit"),
            MagicMock(returncode=0, stdout=json.dumps([{"databaseId": 500, "status": "in_progress"}])),
        ]

        with patch("sys.stderr", new_callable=io.StringIO):
            res = pulse_guard.get_active_runs(
                "sync_upstream.yml", current_run_id="999", backoff_delays=[0, 0, 0]
            )

        self.assertEqual(res.status, "OK")
        self.assertEqual(res.runs, [500])
        self.assertEqual(res.attempts, 3)
        self.assertIsNone(res.error)

    # =========================================================================
    # OUTROS TESTES DE REGRESSÃO
    # =========================================================================
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_manual_does_not_loop(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "manual"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("PULSE_MODE=manual", stdout)
        self.assertIn("NEXT_PULSE_REQUIRED=false", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_NOT_REQUIRED", stdout)

    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_watchdog_restart(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        mock_run.return_value = MagicMock(returncode=0)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("PULSE_MODE=watchdog", stdout)
        self.assertIn("WATCHDOG_RESTART=true", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DISPATCHED", stdout)

    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_exhausts_dispatch_retries(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        mock_run.side_effect = [
            MagicMock(returncode=1, stderr="Fatal API err 1"),
            MagicMock(returncode=1, stderr="Fatal API err 2"),
            MagicMock(returncode=1, stderr="Fatal API err 3"),
        ]
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            with patch("sys.stderr", new_callable=io.StringIO):
                ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 1)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=3", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=FAILED", stdout)


if __name__ == "__main__":
    unittest.main()
