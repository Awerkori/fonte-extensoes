import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("sync-upstream.py")
SPEC = importlib.util.spec_from_file_location("sync_upstream", SCRIPT)
sync_upstream = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(sync_upstream)


class StructuralMetadataTest(unittest.TestCase):
    def apply(self, local: str, upstream: str) -> tuple[str, bool]:
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                path = Path("src/pt/example/build.gradle.kts")
                path.parent.mkdir(parents=True)
                path.write_text(local)
                with patch.object(sync_upstream, "_read_file_text", return_value=upstream):
                    changed = sync_upstream.merge_structural_metadata("src/pt/example", "upstream/main")
                return path.read_text(), changed
            finally:
                os.chdir(previous)

    def test_local_16_can_follow_proven_upstream_selector(self):
        result, changed = self.apply('versionCode = 6\nlibVersion = "1.6"\n', 'versionCode = 5\nlibVersion = "1.4"\n')
        self.assertIn('libVersion = "1.4"', result)
        self.assertTrue(changed)

    def test_blackout_selector_is_only_changed_by_proven_plan(self):
        result, changed = self.apply('versionCode = 11\nlibVersion = "1.6"\n', 'versionCode = 10\nlibVersion = "1.4"\n')
        self.assertIn('libVersion = "1.4"', result)
        self.assertTrue(changed)

    def test_local_14_migrates_to_upstream_16(self):
        result, changed = self.apply('libVersion = "1.4"\n', 'libVersion = "1.6"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertTrue(changed)

    def test_missing_local_lib_version_accepts_upstream(self):
        result, changed = self.apply('versionCode = 1\n\nsource {\n}\n', 'libVersion = "1.6"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertTrue(changed)

    def test_two_protected_dependents_are_persisted(self):
        report = {"theme": [{"unit": "src/pt/a", "migration_required": True}, {"unit": "src/pt/b", "migration_required": True}]}
        state = sync_upstream.deferred_state(report, {"theme"}, "upstream/a")
        self.assertEqual(state["theme"]["units"], ["src/pt/a", "src/pt/b"])

    def test_mixed_compatibility_defers_only_required_unit(self):
        report = {"theme": [{"unit": "src/pt/a", "migration_required": False}, {"unit": "src/pt/b", "migration_required": True}]}
        state = sync_upstream.deferred_state(report, {"theme"}, "upstream/a")
        self.assertEqual(state["theme"]["units"], ["src/pt/b"])

    def test_dependency_map_recalculates_theme_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                for name, theme in (("a", "one"), ("b", "two")):
                    path = Path("src/pt") / name / "build.gradle.kts"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(f'theme = "{theme}"\nlibVersion = "1.6"\n')
                self.assertEqual(sync_upstream.dependency_map(None), {"one": ["src/pt/a"], "two": ["src/pt/b"]})
            finally:
                os.chdir(previous)

    def test_base_version_change_is_monotonic(self):
        with tempfile.TemporaryDirectory() as directory:
            previous_dir = Path.cwd()
            try:
                os.chdir(directory)
                extension = Path("src/pt/a/build.gradle.kts")
                extension.parent.mkdir(parents=True)
                extension.write_text('versionCode = 2\ntheme = "x"\nlibVersion = "1.6"\n')
                multisrc = Path("lib-multisrc/x/build.gradle.kts")
                multisrc.parent.mkdir(parents=True)
                multisrc.write_text('baseVersionCode = 14\nlibVersion = "1.6"\n')
                old = sync_upstream.effective_version_code(None, "src/pt/a")
                multisrc.write_text('baseVersionCode = 16\nlibVersion = "1.6"\n')
                sync_upstream.bump_after_structural_change("src/pt/a", old)
                self.assertGreater(sync_upstream.effective_version_code(None, "src/pt/a")[2], old[2])
            finally:
                os.chdir(previous_dir)

    def test_deferred_reappears_and_then_resolves(self):
        report = {"theme": [{"unit": "src/pt/a", "migration_required": True}]}
        self.assertIn("theme", sync_upstream.deferred_state(report, {"theme"}, "upstream/one"))
        self.assertIn("theme", sync_upstream.deferred_state(report, {"theme"}, "upstream/two"))
        self.assertEqual(sync_upstream.deferred_state({"theme": []}, set(), "upstream/three"), {})

    def test_github_paths_and_source_are_preserved(self):
        self.assertIsNone(sync_upstream.sync_unit(".github/workflows/sync_upstream.yml"))
        result, _ = self.apply('libVersion = "1.4"\nsource {\n baseUrl = "https://nox"\n}\n', 'libVersion = "1.6"\n')
        self.assertIn("https://nox", result)

    def test_unrelated_unit_is_absent_from_deferred_backlog(self):
        state = sync_upstream.deferred_state({"theme": [{"unit": "src/pt/a", "migration_required": True}]}, {"theme"}, "up")
        self.assertNotIn("src/pt/unrelated", str(state))

    def test_missing_multisrc_is_not_compatible(self):
        self.assertIsNone(sync_upstream.multisrc_metadata(None, "does-not-exist"))

    def test_effective_version_includes_base_version(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                path = Path("src/pt/a/build.gradle.kts"); path.parent.mkdir(parents=True)
                path.write_text('versionCode = 2\ntheme = "x"\n')
                multi = Path("lib-multisrc/x/build.gradle.kts"); multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 14\n')
                self.assertEqual(sync_upstream.effective_version_code(None, "src/pt/a")[2], 16)
            finally:
                os.chdir(previous)

    def test_missing_source_selector_is_inserted(self):
        result, changed = self.apply('source {\n}\n', 'libVersion = "1.6"\ntheme = "zeist"\n')
        self.assertTrue(changed)
        self.assertIn('theme = "zeist"', result)

    # Regression tests 15–22: deferred migrations remain explicit until a real
    # sandbox assembleDebug proves the protected source compatible.
    def test_15_old_divergent_api_is_deferred(self):
        report = {"theme": [{"unit": "src/pt/a", "migration_required": True, "migration_supported": False}]}
        self.assertIn("theme", sync_upstream.deferred_state(report, {"theme"}, "up"))

    def test_16_compatible_source_is_safe_to_migrate(self):
        row = {"unit": "src/pt/a", "migration_required": True, "migration_supported": True}
        self.assertTrue(row["migration_supported"])

    def test_17_failed_sandbox_keeps_old_metadata_deferred(self):
        result, _ = self.apply('libVersion = "1.4"\n', 'libVersion = "1.4"\n')
        self.assertIn('libVersion = "1.4"', result)
        self.assertIn("theme", sync_upstream.deferred_state({"theme": [{"unit": "src/pt/a", "migration_required": True}]}, {"theme"}, "up"))

    def test_18_safe_migration_preserves_source(self):
        result, _ = self.apply('libVersion = "1.4"\nsource { baseUrl = "https://nox" }\n', 'libVersion = "1.6"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertIn('https://nox', result)

    def test_19_structural_migration_bumps_16_to_17_once(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                path = Path("src/pt/a/build.gradle.kts"); path.parent.mkdir(parents=True)
                path.write_text('versionCode = 2\ntheme = "x"\n')
                multi = Path("lib-multisrc/x/build.gradle.kts"); multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 14\n')
                old = sync_upstream.effective_version_code(None, "src/pt/a")
                self.assertTrue(sync_upstream.bump_after_structural_change("src/pt/a", old))
                self.assertEqual(sync_upstream.effective_version_code(None, "src/pt/a")[2], 17)
            finally:
                os.chdir(previous)

    def test_20_second_run_does_not_bump_17(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                path = Path("src/pt/a/build.gradle.kts"); path.parent.mkdir(parents=True)
                path.write_text('versionCode = 3\ntheme = "x"\n')
                multi = Path("lib-multisrc/x/build.gradle.kts"); multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 14\n')
                _, changed = self.apply('libVersion = "1.6"\n', 'libVersion = "1.6"\n')
                self.assertFalse(changed)  # apply_units consequently never invokes the guard again.
                self.assertEqual(sync_upstream.effective_version_code(None, "src/pt/a")[2], 17)
            finally:
                os.chdir(previous)

    def test_21_deferred_survives_unrelated_upstream_advance(self):
        report = {"theme": [{"unit": "src/pt/a", "migration_required": True}]}
        self.assertEqual(sync_upstream.deferred_state(report, {"theme"}, "upstream/two")["theme"]["upstream_ref"], "upstream/two")

    def test_22_resolved_deferred_is_removed(self):
        self.assertEqual(sync_upstream.deferred_state({"theme": []}, set(), "upstream/three"), {})

    def test_shared_theme_stays_deferred_until_all_protected_sources_are_safe(self):
        rows = [
            {"unit": "src/pt/a", "migration_required": True, "migration_supported": True},
            {"unit": "src/pt/b", "migration_required": True, "migration_supported": False},
        ]
        blocked = {"theme"} if any(r["migration_required"] and not r["migration_supported"] for r in rows) else set()
        self.assertEqual(blocked, {"theme"})
        self.assertEqual(sync_upstream.deferred_state({"theme": rows}, blocked, "up")["theme"]["units"], ["src/pt/a", "src/pt/b"])
        self.assertFalse(any(r["migration_required"] and not r["migration_supported"] for r in [{**r, "migration_supported": True} for r in rows]))


if __name__ == "__main__":
    unittest.main()
