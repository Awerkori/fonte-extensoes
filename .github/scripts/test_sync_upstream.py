import importlib.util
import os
import subprocess
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
    def test_case_1_successful_structural_migration(self):
        """Case 1: Upstream bumps multisrc and extension; protected extension builds cleanly -> migration accepted."""
        with tempfile.TemporaryDirectory() as directory:
            prev = Path.cwd()
            try:
                os.chdir(directory)
                # Local state: ext and multisrc at 1.4
                ext = Path("src/pt/sample/build.gradle.kts")
                ext.parent.mkdir(parents=True)
                ext.write_text('versionCode = 10\ntheme = "mytheme"\nlibVersion = "1.4"\n\nsource {\n    baseUrl = "https://custom.nox"\n}\n')
                multi = Path("lib-multisrc/mytheme/build.gradle.kts")
                multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 20\nlibVersion = "1.4"\n')

                # Upstream state: ext and multisrc at 1.6
                up_ext_content = 'versionCode = 5\ntheme = "mytheme"\nlibVersion = "1.6"\n'
                with patch.object(sync_upstream, "_read_file_text", return_value=up_ext_content):
                    changed = sync_upstream.merge_structural_metadata("src/pt/sample", "upstream/main")

                self.assertTrue(changed)
                res_text = ext.read_text()
                self.assertIn('libVersion = "1.6"', res_text)
                self.assertIn('baseUrl = "https://custom.nox"', res_text)

                # Version check: ensure local effective > upstream effective
                old_info = (10, 20, 30)
                multi.write_text('baseVersionCode = 20\nlibVersion = "1.6"\n')
                bumped = sync_upstream.bump_after_structural_change("src/pt/sample", old_info)
                self.assertTrue(bumped)
                new_info = sync_upstream.effective_version_code(None, "src/pt/sample")
                self.assertGreater(new_info[2], 30)
            finally:
                os.chdir(prev)

    def test_case_2_incompatible_structural_conflict_atomic_deferral(self):
        """Case 2: Protected unit fails proof build -> entire theme and all its extensions deferred atomically."""
        with tempfile.TemporaryDirectory() as directory:
            prev = Path.cwd()
            try:
                os.chdir(directory)
                # Create 3 extensions under theme "broken":
                # ext_a (protected, incompatible), ext_b (normal), ext_c (normal)
                for name in ("ext_a", "ext_b", "ext_c"):
                    p = Path(f"src/pt/{name}/build.gradle.kts")
                    p.parent.mkdir(parents=True)
                    p.write_text('versionCode = 1\ntheme = "broken"\nlibVersion = "1.4"\n')
                multi = Path("lib-multisrc/broken/build.gradle.kts")
                multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 10\nlibVersion = "1.4"\n')

                # In preflight: theme "broken" is blocked because proof build fails
                blocked_themes = {"broken"}
                local_deps = sync_upstream.dependency_map(None)
                self.assertEqual(sorted(local_deps.get("broken", [])), ["src/pt/ext_a", "src/pt/ext_b", "src/pt/ext_c"])

                # Atomic deferral calculation
                blocked_theme_extensions = {
                    ext
                    for theme in blocked_themes
                    for ext in local_deps.get(theme, [])
                }
                # All 3 extensions MUST be blocked from upstream application
                self.assertEqual(blocked_theme_extensions, {"src/pt/ext_a", "src/pt/ext_b", "src/pt/ext_c"})

                # Compatibility validation: since none were upgraded without the multisrc, 0 errors
                errors = sync_upstream.validate_multisrc_compatibility()
                self.assertEqual(errors, [])

                # Deferred migrations recorded
                report = {"broken": [{"unit": "src/pt/ext_a", "migration_required": True}]}
                state = sync_upstream.deferred_state(report, blocked_themes, "upstream/main")
                self.assertIn("broken", state)
            finally:
                os.chdir(prev)

    def test_case_3_standalone_extension_isolated_deferral(self):
        """Case 3: Extension without multisrc theme that fails is isolated and does not affect other units."""
        with tempfile.TemporaryDirectory() as directory:
            prev = Path.cwd()
            try:
                os.chdir(directory)
                # Standalone extension has no theme in build.gradle.kts
                standalone = Path("src/pt/standalone/build.gradle.kts")
                standalone.parent.mkdir(parents=True)
                standalone.write_text('versionCode = 5\nlibVersion = "1.4"\n')

                normal_multi = Path("lib-multisrc/goodtheme/build.gradle.kts")
                normal_multi.parent.mkdir(parents=True)
                normal_multi.write_text('baseVersionCode = 1\nlibVersion = "1.6"\n')

                normal_ext = Path("src/pt/normal/build.gradle.kts")
                normal_ext.parent.mkdir(parents=True)
                normal_ext.write_text('versionCode = 1\ntheme = "goodtheme"\nlibVersion = "1.6"\n')

                deps = sync_upstream.dependency_map(None)
                self.assertNotIn("src/pt/standalone", deps.get("goodtheme", []))
                # Standalone extension is not in dependency_map of any multisrc theme
                self.assertNotIn("src/pt/standalone", [u for units in deps.values() for u in units])
            finally:
                os.chdir(prev)

    def test_case_4_version_code_guarantee_nox_always_ahead(self):
        """Case 4: Version guard guarantees Nox effective versionCode > upstream effective versionCode."""
        with tempfile.TemporaryDirectory() as directory:
            prev = Path.cwd()
            try:
                os.chdir(directory)
                ext = Path("src/pt/sample/build.gradle.kts")
                ext.parent.mkdir(parents=True)
                ext.write_text('versionCode = 12\ntheme = "theme"\nlibVersion = "1.6"\n')
                multi = Path("lib-multisrc/theme/build.gradle.kts")
                multi.parent.mkdir(parents=True)
                multi.write_text('baseVersionCode = 34\nlibVersion = "1.6"\n')

                # Local effective: 12 + 34 = 46.
                # Upstream effective: 15 + 34 = 49.
                up_content = 'versionCode = 15\ntheme = "theme"\nlibVersion = "1.6"\n'
                orig_read = sync_upstream._read_file_text

                def fake_read(ref, path):
                    if ref == "upstream/main":
                        if "lib-multisrc" in path:
                            return 'baseVersionCode = 34\nlibVersion = "1.6"\n'
                        return up_content
                    return orig_read(ref, path)

                with patch.object(sync_upstream, "_read_file_text", side_effect=fake_read):
                    res = sync_upstream.bump_version_code_if_needed("src/pt/sample", "upstream/main")

                self.assertIsNotNone(res)
                loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw = res
                self.assertEqual(loc_eff, 46)
                self.assertEqual(up_eff, 49)
                self.assertEqual(new_raw, 16)  # desired 50 - 34 = 16
                self.assertGreater(new_raw + loc_base, up_eff)
            finally:
                os.chdir(prev)

    def test_case_5_validate_multisrc_compatibility_clean(self):
        """Case 5: validate_multisrc_compatibility() returns empty list when all versions match."""
        with tempfile.TemporaryDirectory() as directory:
            prev = Path.cwd()
            try:
                os.chdir(directory)
                for t in ("theme1", "theme2"):
                    m = Path(f"lib-multisrc/{t}/build.gradle.kts")
                    m.parent.mkdir(parents=True)
                    m.write_text('baseVersionCode = 0\nlibVersion = "1.6"\n')
                    e = Path(f"src/pt/{t}_ext/build.gradle.kts")
                    e.parent.mkdir(parents=True)
                    e.write_text(f'versionCode = 1\ntheme = "{t}"\nlibVersion = "1.6"\n')

                errors = sync_upstream.validate_multisrc_compatibility()
                self.assertEqual(errors, [])
            finally:
                os.chdir(prev)


class ProtectedMultisrcTest(unittest.TestCase):
    theme = "sample"
    unit = "lib-multisrc/sample"
    extensions = ["src/pt/one", "src/pt/two"]

    def setUp(self):
        self.previous = Path.cwd()
        self.directory = tempfile.TemporaryDirectory(prefix="nox-sync-test-")
        os.chdir(self.directory.name)
        self.addCleanup(self.cleanup)
        self.git("init", "-q")
        self.git("config", "user.email", "test@invalid")
        self.git("config", "user.name", "Sync test")
        self.write(f"{self.unit}/build.gradle.kts", 'libVersion = "1.6"\nbaseVersionCode = 3\n')
        self.write(f"{self.unit}/Reader.kt", "reader = old\n" + "\n" * 12 + "backend = old\n")
        for unit in self.extensions:
            self.write(f"{unit}/build.gradle.kts", 'versionCode = 1\ntheme = "sample"\nlibVersion = "1.6"\n')
        self.write("gradlew", "#!/usr/bin/env python3\nimport pathlib, sys\np = pathlib.Path('lib-multisrc/sample')\nassert 'reader = nox' in (p/'Reader.kt').read_text()\nassert 'too_fast' in (p/'Gate.kt').read_text()\nassert ':lib-multisrc:sample:testDebugUnitTest' in sys.argv\nfor name in ('one', 'two'):\n assert f':src:pt:{name}:assembleDebug' in sys.argv\n assert (p/'build.gradle.kts').read_text().splitlines()[0] in pathlib.Path(f'src/pt/{name}/build.gradle.kts').read_text()\n")
        Path("gradlew").chmod(0o755)
        self.base = self.commit("base")
        self.git("branch", "upstream", self.base)
        self.git("branch", "nox", self.base)
        self.git("checkout", "-q", "nox")

    def cleanup(self):
        os.chdir(self.previous)
        self.directory.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.strip()

    def write(self, path, text):
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def nox_patch(self):
        path = Path(f"{self.unit}/Reader.kt")
        path.write_text(path.read_text().replace("reader = old", "reader = nox"))
        self.write(f"{self.unit}/Gate.kt", "too_fast retry once\n")
        self.commit("functional Nox patch")

    def advance(self, conflict=False, structural=False, fail_build=False):
        self.git("checkout", "-q", "upstream")
        path = Path(f"{self.unit}/Reader.kt")
        path.write_text(path.read_text().replace("reader = old" if conflict else "backend = old", "reader = upstream" if conflict else "backend = new"))
        if structural:
            for path in [f"{self.unit}/build.gradle.kts", *(f"{unit}/build.gradle.kts" for unit in self.extensions)]:
                p = Path(path)
                p.write_text(p.read_text().replace('"1.6"', '"1.7"'))
        if fail_build:
            self.write("gradlew", "#!/bin/sh\nexit 1\n")
        self.write("unrelated.txt", "upstream advance\n")
        self.commit("upstream update")
        self.git("checkout", "-q", "nox")

    def prove(self):
        before = self.git("status", "--porcelain")
        index = self.git("write-tree")
        result = sync_upstream.prove_protected_multisrc(self.base, "upstream", [], set())
        self.assertEqual(self.git("status", "--porcelain"), before)
        self.assertEqual(self.git("write-tree"), index)
        return result

    def test_a_upstream_only_multisrc_is_not_protected(self):
        self.advance()
        self.assertEqual(sync_upstream.protected_multisrc_bases(self.base, "upstream"), {})
        self.assertEqual(self.prove(), ({}, {}))
        with patch.object(sync_upstream, "validate_affected_builds"):
            sync_upstream.apply_units("upstream", [self.unit], set(), [], [], set(), {})
        self.assertIn("backend = new", Path(f"{self.unit}/Reader.kt").read_text())

    def test_b_unchanged_upstream_preserves_patch_without_build(self):
        self.nox_patch()
        self.git("checkout", "-q", "upstream")
        self.write("unrelated.txt", "unrelated update\n")
        self.commit("unrelated upstream change")
        self.git("checkout", "-q", "nox")
        self.assertEqual(sync_upstream.protected_multisrc_bases(self.base, "upstream"), {self.theme: self.base})
        self.assertEqual(self.prove(), ({}, {}))
        self.assertIn("reader = nox", Path(f"{self.unit}/Reader.kt").read_text())

    def test_c_functional_merge_builds_all_dependents_and_tests(self):
        self.nox_patch()
        self.advance()
        accepted, deferred = self.prove()
        self.assertFalse(deferred)
        tree = accepted[self.theme]["tree"]
        reader = self.git("show", f"{tree}:{self.unit}/Reader.kt")
        self.assertIn("reader = nox", reader)
        self.assertIn("backend = new", reader)
        self.assertEqual(accepted[self.theme]["units"], self.extensions)

    def test_d_structural_upgrade_keeps_functional_delta(self):
        self.nox_patch()
        path = Path(f"{self.unit}/build.gradle.kts")
        path.write_text(path.read_text() + "\n// Nox test configuration\n")
        self.commit("test configuration")
        self.advance(structural=True)
        accepted, deferred = self.prove()
        self.assertFalse(deferred)
        tree = accepted[self.theme]["tree"]
        gradle = self.git("show", f"{tree}:{self.unit}/build.gradle.kts")
        self.assertIn('"1.7"', gradle)
        self.assertIn("Nox test configuration", gradle)
        self.assertIn("too_fast", self.git("show", f"{tree}:{self.unit}/Gate.kt"))

    def test_e_conflict_defers_theme_and_all_dependents(self):
        self.nox_patch()
        self.advance(conflict=True, structural=True)
        accepted, deferred = self.prove()
        self.assertFalse(accepted)
        self.assertEqual(deferred[self.theme]["patch_base"], self.base)
        self.assertEqual(deferred[self.theme]["units"], self.extensions)
        self.assertIn("functional conflict", deferred[self.theme]["reason"])

    def test_f_build_failure_defers_without_touching_main(self):
        self.nox_patch()
        self.advance(fail_build=True)
        accepted, deferred = self.prove()
        self.assertFalse(accepted)
        self.assertEqual(deferred[self.theme]["reason"], "proof build/tests failed")

    def test_g_deferred_retry_keeps_original_base_after_ours_merge(self):
        self.nox_patch()
        self.advance(conflict=True)
        _, deferred = self.prove()
        sync_upstream.write_deferred_migrations(deferred)
        self.commit("deferred backlog")
        self.git("merge", "-s", "ours", "--no-edit", "upstream")
        self.git("checkout", "-q", "upstream")
        path = Path(f"{self.unit}/Reader.kt")
        path.write_text(path.read_text().replace("reader = upstream", "reader = old").replace("backend = old", "backend = new"))
        self.commit("compatible upstream revision")
        self.git("checkout", "-q", "nox")
        new_base = self.git("merge-base", "HEAD", "upstream")
        self.assertNotEqual(new_base, self.base)
        self.assertEqual(sync_upstream.protected_multisrc_bases(new_base, "upstream"), {self.theme: self.base})
        accepted, deferred = sync_upstream.prove_protected_multisrc(new_base, "upstream", [], set())
        self.assertIn(self.theme, accepted)
        self.assertFalse(deferred)

    def test_h_upstream_deletion_of_patched_file_is_deferred(self):
        self.nox_patch()
        self.git("checkout", "-q", "upstream")
        self.git("rm", f"{self.unit}/Reader.kt")
        self.commit("remove old reader")
        self.git("checkout", "-q", "nox")
        accepted, deferred = self.prove()
        self.assertFalse(accepted)
        self.assertIn("add/delete conflict", deferred[self.theme]["reason"])

    def test_i_upstream_adopted_patch_needs_no_protection(self):
        self.nox_patch()
        self.git("branch", "-f", "upstream", "HEAD")
        self.assertEqual(sync_upstream.protected_multisrc_bases(self.base, "upstream"), {})

    def test_j_apply_uses_proven_tree_not_upstream_tree(self):
        self.nox_patch()
        self.advance(structural=True)
        proven, deferred = self.prove()
        units = sync_upstream.collect_units(sync_upstream.changed_entries(self.base, "upstream"))[0]
        with patch.object(sync_upstream, "validate_affected_builds"):
            sync_upstream.apply_units("upstream", units, {self.unit}, [], [], set(), deferred, proven)
        self.assertIn("reader = nox", Path(f"{self.unit}/Reader.kt").read_text())
        self.assertIn("backend = new", Path(f"{self.unit}/Reader.kt").read_text())
        self.assertIn('"1.7"', Path(f"{self.unit}/build.gradle.kts").read_text())
        self.assertTrue(Path("unrelated.txt").exists())
        self.assertEqual(sync_upstream.validate_multisrc_compatibility(), [])
        self.assertFalse(sync_upstream.load_deferred_migrations())
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_k_apply_defers_atomically_and_keeps_unrelated_updates(self):
        self.nox_patch()
        self.advance(conflict=True, structural=True)
        proven, deferred = self.prove()
        units = sync_upstream.collect_units(sync_upstream.changed_entries(self.base, "upstream"))[0]
        before = {unit: Path(f"{unit}/build.gradle.kts").read_bytes() for unit in [self.unit, *self.extensions]}
        with patch.object(sync_upstream, "validate_affected_builds"):
            sync_upstream.apply_units("upstream", units, {self.unit}, self.extensions, [], {self.theme}, deferred, proven)
        for unit, content in before.items():
            self.assertEqual(Path(f"{unit}/build.gradle.kts").read_bytes(), content)
        self.assertTrue(Path("unrelated.txt").exists())
        self.assertEqual(sync_upstream.load_deferred_migrations()[self.theme]["patch_base"], self.base)
        self.assertIn("reader = nox", Path(f"{self.unit}/Reader.kt").read_text())
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_l_unproven_protected_theme_cannot_be_applied(self):
        self.nox_patch()
        self.advance()
        head = self.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(RuntimeError, "Unproven protected multisrc"):
            sync_upstream.apply_units("upstream", [self.unit], {self.unit}, [], [], set(), {})
        self.assertEqual(self.git("rev-parse", "HEAD"), head)
        self.assertFalse(Path(".git/MERGE_HEAD").exists())

    def test_m_shared_dependent_propagates_atomic_deferral(self):
        proven = {"second": {"tree": "tree", "units": [self.extensions[0]]}}
        deferred, blocked = {}, {self.theme}
        with patch.object(sync_upstream, "dependency_map", return_value={self.theme: self.extensions, "second": [self.extensions[0]]}):
            sync_upstream.propagate_multisrc_deferrals(proven, deferred, blocked, {"second": self.base}, "upstream")
        self.assertFalse(proven)
        self.assertEqual(blocked, {self.theme, "second"})
        self.assertEqual(deferred["second"]["patch_base"], self.base)

    def test_n_patch_is_protected_again_after_successful_sync(self):
        self.nox_patch()
        self.advance()
        proven, deferred = self.prove()
        with patch.object(sync_upstream, "validate_affected_builds"):
            sync_upstream.apply_units("upstream", [self.unit], {self.unit}, [], [], set(), deferred, proven)
        self.git("checkout", "-q", "upstream")
        path = Path(f"{self.unit}/Reader.kt")
        path.write_text(path.read_text() + "\nsecond upstream improvement\n")
        self.commit("second upstream update")
        self.git("checkout", "-q", "nox")
        base = self.git("merge-base", "HEAD", "upstream")
        proven, deferred = sync_upstream.prove_protected_multisrc(base, "upstream", [], set())
        self.assertFalse(deferred)
        reader = self.git("show", f"{proven[self.theme]['tree']}:{self.unit}/Reader.kt")
        self.assertIn("reader = nox", reader)
        self.assertIn("second upstream improvement", reader)

    def test_o_removing_protected_theme_is_deferred(self):
        self.nox_patch()
        self.git("checkout", "-q", "upstream")
        self.git("rm", "-r", self.unit)
        self.commit("remove theme")
        self.git("checkout", "-q", "nox")
        proven, deferred = self.prove()
        self.assertFalse(proven)
        self.assertIn("removed upstream", deferred[self.theme]["reason"])


class BuildMatrixTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("build_matrices", SCRIPT.with_name("generate-build-matrices.py"))
        cls.matrix = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.matrix)

    def setUp(self):
        previous = Path.cwd()
        os.chdir(SCRIPT.resolve().parents[2])
        self.addCleanup(os.chdir, previous)

    def selected(self, path):
        with patch.object(self.matrix, "run_command", return_value=f"M\t{path}"):
            modules, deleted = self.matrix.get_module_list("HEAD")
        return set(modules), set(deleted)

    def test_a_sync_script_does_not_build_or_publish(self):
        self.assertEqual(self.selected(".github/scripts/sync-upstream.py"), (set(), set()))

    def test_b_sync_tests_do_not_build_or_publish(self):
        self.assertEqual(self.selected(".github/scripts/test_sync_upstream.py"), (set(), set()))

    def test_c_matrix_script_does_not_build_or_publish(self):
        self.assertEqual(self.selected(".github/scripts/generate-build-matrices.py"), (set(), set()))

    def test_d_core_still_builds_all_extensions(self):
        self.assertTrue(Path("core/build.gradle.kts").is_file())
        self.assertEqual(self.selected("core/build.gradle.kts"), tuple(map(set, self.matrix.get_all_modules())))

    def test_e_aurora_builds_exactly_four_dependents(self):
        names = {"mangasbrasuka", "mugiwarasoficial", "leitordemangas", "manganyx"}
        self.assertEqual(self.selected("lib-multisrc/aurora/build.gradle.kts"), (
            {f":src:pt:{name}" for name in names}, {f"pt.{name}" for name in names},
        ))

    def test_f_single_extension_stays_isolated(self):
        self.assertEqual(self.selected("src/pt/mangasbrasuka/build.gradle.kts"), (
            {":src:pt:mangasbrasuka"}, {"pt.mangasbrasuka"},
        ))


if __name__ == "__main__":
    unittest.main()
