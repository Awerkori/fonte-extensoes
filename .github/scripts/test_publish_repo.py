import copy
import gzip
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

from google.protobuf import json_format

import github_utils
import index_pb2
from legacy_index import update_legacy_index


PREFIX = "eu.kanade.tachiyomi.extension.pt."
SCRIPT = Path(__file__).with_name("publish-repo.py")


def extension(slug="geasscomics", version=5):
    return index_pb2.Extension(
        name={"geasscomics": "Geass Comics", "karikari": "KariKari", "onereader": "OneReader"}.get(slug, slug),
        packageName=PREFIX + slug,
        extensionLib="1.6",
        versionCode=106000 + version,
        versionName=f"1.6.{version}",
        contentWarning=index_pb2.CONTENT_WARNING_MIXED,
        resources=index_pb2.Resources(apkUrl=f"https://example.org/tachiyomi-pt.{slug}-v1.6.{version}.apk"),
        sources=[index_pb2.Source(id=7839519253265657488, name=slug, language="pt-BR", homeUrl="https://example.org")],
    )


def legacy(ext):
    return {
        "name": ext.name, "pkg": ext.packageName,
        "apk": ext.resources.apkUrl.rsplit("/", 1)[-1], "lang": "pt-BR",
        "code": ext.versionCode - 106000, "version": ext.versionName, "nsfw": 0,
        "sources": [{"name": s.name, "lang": s.language, "id": str(s.id), "baseUrl": s.homeUrl} for s in ext.sources],
    }


class LegacyIndexTest(unittest.TestCase):
    def setUp(self):
        self.old = [legacy(extension()), legacy(extension("karikari", 1)), legacy(extension("onereader", 3))]

    def test_existing_update_and_encoded_version(self):
        result = update_legacy_index(self.old, [extension(version=6)], ["pt.geasscomics"])
        item = next(e for e in result if e["pkg"] == PREFIX + "geasscomics")
        self.assertEqual((item["code"], item["version"], item["apk"]), (6, "1.6.6", "tachiyomi-pt.geasscomics-v1.6.6.apk"))

    def test_new_extension(self):
        result = update_legacy_index(self.old, [extension("new", 1)], ["pt.new"])
        self.assertIn(legacy(extension("new", 1)), result)

    def test_explicit_removal_only(self):
        result = update_legacy_index(self.old, [], ["pt.geasscomics"])
        self.assertEqual(result, self.old[1:])

    def test_unrelated_entries_and_protected_versions_unchanged(self):
        before = copy.deepcopy(self.old)
        self.old[1]["extra"] = {"preserve": True}
        result = update_legacy_index(self.old, [extension(version=6)], ["pt.geasscomics"])
        self.assertEqual(result[1:], self.old[1:])
        self.assertEqual([e["version"] for e in result[1:]], ["1.6.1", "1.6.3"])
        self.assertEqual(self.old[0], before[0])

    def test_source_metadata_and_compatible_fields(self):
        self.old[0]["sources"][0]["extra"] = "keep"
        updated = extension(version=6)
        updated.name = "New title"
        updated.sources[0].name = "New source"
        updated.sources[0].language = "en"
        updated.sources[0].homeUrl = "https://new.example"
        updated.sources.add(id=42, name="Second", language="es", homeUrl="https://es.example")
        result = update_legacy_index(self.old, [updated], ["pt.geasscomics"])[0]
        self.assertEqual(result["name"], "New title")
        self.assertEqual(result["lang"], "en")
        self.assertEqual(result["sources"][0], {"id": "7839519253265657488", "name": "New source", "lang": "en", "baseUrl": "https://new.example", "extra": "keep"})
        self.assertEqual(result["sources"][1]["id"], "42")

    def test_warning_and_other_library_version(self):
        ext = extension(version=6)
        ext.extensionLib = "1.4"
        ext.versionCode = 104006
        ext.versionName = "1.4.6"
        for warning, expected in [(1, 0), (2, 0), (3, 1)]:
            ext.contentWarning = warning
            item = update_legacy_index([], [ext], [])[0]
            self.assertEqual((item["code"], item["nsfw"]), (6, expected))

    def test_deterministic_noop(self):
        result = update_legacy_index(list(reversed(self.old)), [], [])
        self.assertEqual(result, self.old)
        self.assertEqual(update_legacy_index(result, [], []), result)

    def test_inconsistent_version_fails(self):
        ext = extension(version=6)
        ext.versionCode = 106005
        with self.assertRaises(ValueError):
            update_legacy_index([], [ext], [])


class PublisherTest(unittest.TestCase):
    def simulate(self, modern, old, update=None, deleted=()):
        with tempfile.TemporaryDirectory(prefix="nox-publisher-test-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")
            if update:
                build = root / "apk-artifacts" / "module" / "build"
                for kind in ("apk", "jar"):
                    output = build / "outputs" / kind / "release"
                    output.mkdir(parents=True)
                    (output / f"tachiyomi-pt.geasscomics-v1.6.6.{kind}").write_bytes(b"fixture-only")
                info = json_format.MessageToDict(update, preserving_proto_field_name=True)
                info["versionCode"] = int(info["versionCode"])
                info["contentWarning"] = update.contentWarning
                info["module"] = "pt.geasscomics"
                info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in update.sources]
                (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")
            calls = []

            def github(*args, **kwargs):
                calls.append(args)
                return '{"assets":[]}' if args[:2] == ("release", "view") and "assets" in args else ""

            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", side_effect=github), patch("sys.argv", [str(SCRIPT), json.dumps(list(deleted)), "fixture123456"]):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as error:
                        self.assertEqual(error.code, 0)
                generated = json.loads((root / "index.min.json").read_text())
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                binary = index_pb2.Index.FromString(gzip.decompress((root / "index.pb").read_bytes()))
                self.assertEqual(current, binary)
                self.assertTrue((root / "index.html").exists())
                self.assertTrue((root / "release-assets.json").exists())
                return generated, current, calls
            finally:
                os.chdir(previous)

    def test_full_publisher_update_and_removal(self):
        extensions = [extension(), extension("karikari", 1), extension("onereader", 3)]
        old = [legacy(e) for e in extensions]
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=extensions))
        generated, current, calls = self.simulate(modern, old, extension(version=6), ["pt.geasscomics"])
        self.assertEqual(generated[0]["code"], 6)
        self.assertEqual(generated[1:], old[1:])
        self.assertEqual(current.extensionList.extensions[0].versionCode, 106006)
        self.assertTrue(calls)
        removed, _, calls = self.simulate(modern, old, deleted=["pt.geasscomics"])
        self.assertEqual(removed, old[1:])
        self.assertEqual(calls, [])

    @unittest.skipUnless(os.environ.get("NOX_PUBLISHER_SNAPSHOT"), "optional public snapshot")
    def test_public_snapshot_only_geass_changes(self):
        directory = Path(os.environ["NOX_PUBLISHER_SNAPSHOT"])
        modern = json_format.Parse((directory / "before-index.json").read_text(), index_pb2.Index())
        old = json.loads((directory / "before-legacy.json").read_text())
        unchanged, _, calls = self.simulate(modern, old)
        self.assertEqual({e["pkg"]: e for e in unchanged}, {e["pkg"]: e for e in old})
        self.assertEqual(calls, [])
        ext = next(e for e in modern.extensionList.extensions if e.packageName == PREFIX + "geasscomics")
        update = index_pb2.Extension()
        update.CopyFrom(ext)
        update.versionCode = 106006
        update.versionName = "1.6.6"
        generated, current, _ = self.simulate(modern, old, update, ["pt.geasscomics"])
        before = {e["pkg"]: e for e in old}
        after = {e["pkg"]: e for e in generated}
        self.assertEqual(before.keys(), after.keys())
        changed = [key for key in before if before[key] != after[key]]
        self.assertEqual(changed, [PREFIX + "geasscomics"])
        self.assertEqual(after[PREFIX + "geasscomics"]["code"], 6)
        for slug, version in [("karikari", "1.6.1"), ("onereader", "1.6.3")]:
            self.assertEqual(after[PREFIX + slug]["version"], version)
        before_modern = {e.packageName: e for e in modern.extensionList.extensions}
        after_modern = {e.packageName: e for e in current.extensionList.extensions}
        self.assertEqual(before_modern.keys(), after_modern.keys())
        self.assertEqual([key for key in before_modern if before_modern[key] != after_modern[key]], changed)
        print("PUBLIC SNAPSHOT PASS: zero removals/downgrades; only Geass changes: code 5 -> 6, version 1.6.5 -> 1.6.6, APK v1.6.5 -> v1.6.6; KariKari/OneReader unchanged")


if __name__ == "__main__":
    unittest.main()
