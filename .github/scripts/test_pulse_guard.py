#!/usr/bin/env python3
"""
Unit tests for pulse_guard.py.
"""

import io
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

# Add parent directory to sys.path to import pulse_guard
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pulse_guard


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
        active = pulse_guard.get_active_runs("sync_upstream.yml", current_run_id="999")
        self.assertEqual(active, [101, 102])

    @patch("pulse_guard.get_active_runs")
    def test_watchdog_check_non_watchdog_modes(self, mock_active):
        # heartbeat mode
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        args = MagicMock(event_name=None, trigger_source=None, current_run_id="123", repo=None, workflow="sync_upstream.yml")
        
        ret = pulse_guard.cmd_watchdog_check(args)
        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "true")
        self.assertEqual(outputs.get("pulse_mode"), "heartbeat")
        self.assertEqual(outputs.get("watchdog_restart"), "false")
        mock_active.assert_not_called()

    @patch("pulse_guard.get_active_runs")
    def test_watchdog_check_already_alive(self, mock_active):
        # Watchdog fires, but heartbeat run 101 is already running
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        args = MagicMock(event_name=None, trigger_source=None, current_run_id="999", repo=None, workflow="sync_upstream.yml")
        mock_active.return_value = [101]

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_watchdog_check(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "false")
        self.assertEqual(outputs.get("pulse_mode"), "watchdog")
        self.assertEqual(outputs.get("watchdog_already_alive"), "true")
        self.assertEqual(outputs.get("watchdog_restart"), "false")
        self.assertIn("WATCHDOG_ALREADY_ALIVE=true", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ALREADY_ACTIVE", stdout)

    @patch("pulse_guard.get_active_runs")
    def test_watchdog_check_dead_pulse_restarts(self, mock_active):
        # Watchdog fires, 0 active runs -> must restart
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        args = MagicMock(event_name=None, trigger_source=None, current_run_id="999", repo=None, workflow="sync_upstream.yml")
        mock_active.return_value = []

        ret = pulse_guard.cmd_watchdog_check(args)
        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "true")
        self.assertEqual(outputs.get("pulse_mode"), "watchdog")
        self.assertEqual(outputs.get("watchdog_restart"), "true")
        self.assertEqual(outputs.get("watchdog_already_alive"), "false")

    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_manual_does_not_loop(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "manual"
        mock_active.return_value = []
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

    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_heartbeat_skipped_if_active(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = [200]
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("PULSE_MODE=heartbeat", stdout)
        self.assertIn("OTHER_ACTIVE_RUNS=1", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ALREADY_ACTIVE", stdout)

    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_success_first_attempt(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = []
        mock_run.return_value = MagicMock(returncode=0)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("PULSE_MODE=heartbeat", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=1", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DISPATCHED", stdout)
        self.assertIn("WATCHDOG_RESTART=false", stdout)

    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_retry_success(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = []
        # First call fails, second succeeds
        mock_run.side_effect = [
            MagicMock(returncode=1, stderr="Rate limit"),
            MagicMock(returncode=0),
        ]
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=2", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DISPATCHED", stdout)

    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_pulse_dispatch_watchdog_restart(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        mock_active.return_value = []
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
    def test_pulse_dispatch_exhausts_retries(self, mock_active, mock_run):
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = []
        # All 3 calls fail
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
            ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 1)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=3", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=FAILED", stdout)


if __name__ == "__main__":
    unittest.main()
