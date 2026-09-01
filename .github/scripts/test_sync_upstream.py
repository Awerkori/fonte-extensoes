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

    def test_local_16_wins_over_upstream_14(self):
        result, changed = self.apply('versionCode = 6\nlibVersion = "1.6"\n', 'versionCode = 5\nlibVersion = "1.4"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertFalse(changed)

    def test_blackout_local_16_wins_over_upstream_14(self):
        result, changed = self.apply('versionCode = 11\nlibVersion = "1.6"\n', 'versionCode = 10\nlibVersion = "1.4"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertFalse(changed)

    def test_local_14_wins_over_upstream_16(self):
        result, changed = self.apply('libVersion = "1.4"\n', 'libVersion = "1.6"\n')
        self.assertIn('libVersion = "1.4"', result)
        self.assertFalse(changed)

    def test_missing_local_lib_version_accepts_upstream(self):
        result, changed = self.apply('versionCode = 1\n\nsource {\n}\n', 'libVersion = "1.6"\n')
        self.assertIn('libVersion = "1.6"', result)
        self.assertTrue(changed)


if __name__ == "__main__":
    unittest.main()
