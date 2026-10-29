import copy
import gzip
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

try:
    from google.protobuf import json_format
    import index_pb2
    from legacy_index import update_legacy_index
    HAS_PROTOBUF = True
except ImportError:
    HAS_PROTOBUF = False
    update_legacy_index = None

import github_utils



PREFIX = "eu.kanade.tachiyomi.extension.pt."
SCRIPT = Path(__file__).with_name("publish-repo.py")


def extension(slug="geasscomics", version=5):
    if not HAS_PROTOBUF:
        return None
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


@unittest.skipUnless(HAS_PROTOBUF, "protobuf is required for legacy index tests")
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


@unittest.skipUnless(HAS_PROTOBUF, "protobuf is required for publisher tests")
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

    def test_case_a_schalenetwork_replaces_koharu_without_duplicate(self):
        koharu_pkg = "eu.kanade.tachiyomi.extension.all.koharu"
        old_koharu = index_pb2.Extension(
            name="SchaleNetwork",
            packageName=koharu_pkg,
            extensionLib="1.6",
            versionCode=106000,
            versionName="1.6.0",
            contentWarning=index_pb2.CONTENT_WARNING_NSFW,
            resources=index_pb2.Resources(
                apkUrl=f"https://example.org/tachiyomi-all.koharu-v1.6.0.apk",
                iconUrl="https://cdn.jsdelivr.net/gh/Awerkori/manga-fonte-extensoes@main/src/all/schalenetwork/res/mipmap-xhdpi/ic_launcher.png",
            ),
            sources=[index_pb2.Source(id=1484902275639232927, name="SchaleNetwork", language="all", homeUrl="https://shupogaki.moe")],
        )
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[old_koharu]))
        old = [legacy(old_koharu)]

        new_koharu = index_pb2.Extension()
        new_koharu.CopyFrom(old_koharu)
        new_koharu.versionCode = 106001
        new_koharu.versionName = "1.6.1"

        with tempfile.TemporaryDirectory(prefix="nox-case-a-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "all.schalenetwork" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-all.koharu-v1.6.1.{kind}").write_bytes(b"new-build")

            info = json_format.MessageToDict(new_koharu, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = new_koharu.contentWarning
            info["module"] = "all.schalenetwork"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in new_koharu.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), json.dumps(["all.schalenetwork"]), "fixture-sha"]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                koharu_entries = [e for e in current.extensionList.extensions if e.packageName == koharu_pkg]
                self.assertEqual(len(koharu_entries), 1)
                self.assertEqual(koharu_entries[0].versionCode, 106001)

                legacy_entries = json.loads((root / "index.min.json").read_text())
                legacy_koharu = [e for e in legacy_entries if e["pkg"] == koharu_pkg]
                self.assertEqual(len(legacy_koharu), 1)
                self.assertEqual(legacy_koharu[0]["code"], 1)

                html_content = (root / "index.html").read_text()
                self.assertEqual(html_content.count("Tachiyomi: SchaleNetwork"), 1)
            finally:
                os.chdir(previous)

    def test_case_b_brainrotcomics_replaces_aryascans(self):
        arya_pkg = "eu.kanade.tachiyomi.extension.en.aryascans"
        old_arya = index_pb2.Extension(
            name="BrainRotComics",
            packageName=arya_pkg,
            extensionLib="1.6",
            versionCode=106057,
            versionName="1.6.57",
            contentWarning=index_pb2.CONTENT_WARNING_MIXED,
            resources=index_pb2.Resources(
                apkUrl=f"https://example.org/tachiyomi-en.aryascans-v1.6.57.apk",
                iconUrl="https://cdn.jsdelivr.net/gh/Awerkori/manga-fonte-extensoes@main/src/en/brainrotcomics/res/mipmap-xhdpi/ic_launcher.png",
            ),
            sources=[index_pb2.Source(id=12345, name="BrainRotComics", language="en", homeUrl="https://brainrot.example")],
        )
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[old_arya]))
        old = [legacy(old_arya)]

        new_arya = index_pb2.Extension()
        new_arya.CopyFrom(old_arya)
        new_arya.versionCode = 106058
        new_arya.versionName = "1.6.58"

        with tempfile.TemporaryDirectory(prefix="nox-case-b-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "en.brainrotcomics" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-en.aryascans-v1.6.58.{kind}").write_bytes(b"new-build")

            info = json_format.MessageToDict(new_arya, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = new_arya.contentWarning
            info["module"] = "en.brainrotcomics"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in new_arya.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), json.dumps(["en.brainrotcomics"]), "fixture-sha"]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                arya_entries = [e for e in current.extensionList.extensions if e.packageName == arya_pkg]
                self.assertEqual(len(arya_entries), 1)
                self.assertEqual(arya_entries[0].versionCode, 106058)
            finally:
                os.chdir(previous)

    def test_case_d_removed_module_with_different_pkgname_deletes_cleanly(self):
        koharu_pkg = "eu.kanade.tachiyomi.extension.all.koharu"
        old_koharu = index_pb2.Extension(
            name="SchaleNetwork",
            packageName=koharu_pkg,
            extensionLib="1.6",
            versionCode=106000,
            versionName="1.6.0",
            contentWarning=index_pb2.CONTENT_WARNING_NSFW,
            resources=index_pb2.Resources(
                apkUrl=f"https://example.org/tachiyomi-all.koharu-v1.6.0.apk",
                iconUrl="https://cdn.jsdelivr.net/gh/Awerkori/manga-fonte-extensoes@main/src/all/schalenetwork/res/mipmap-xhdpi/ic_launcher.png",
            ),
            sources=[index_pb2.Source(id=1484902275639232927, name="SchaleNetwork", language="all", homeUrl="https://shupogaki.moe")],
        )
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[old_koharu, extension("karikari", 1)]))
        old = [legacy(old_koharu), legacy(extension("karikari", 1))]

        with tempfile.TemporaryDirectory(prefix="nox-case-d-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")
            (root / "release-assets.json").write_text(json.dumps({koharu_pkg: {"apk": {"name": "a.apk", "sha256": "123"}}}), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), json.dumps(["all.schalenetwork"]), "fixture-sha"]):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as error:
                        self.assertEqual(error.code, 0)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                koharu_entries = [e for e in current.extensionList.extensions if e.packageName == koharu_pkg]
                self.assertEqual(len(koharu_entries), 0)

                release_assets = json.loads((root / "release-assets.json").read_text())
                self.assertNotIn(koharu_pkg, release_assets)

                legacy_entries = json.loads((root / "index.min.json").read_text())
                self.assertNotIn(koharu_pkg, [e["pkg"] for e in legacy_entries])
            finally:
                os.chdir(previous)

    def test_case_e_duplicate_artifact_package_aborts(self):
        ext1 = extension(slug="geasscomics", version=5)
        ext2 = extension(slug="geasscomics", version=6)

        with tempfile.TemporaryDirectory(prefix="nox-case-e-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(index_pb2.Index()), encoding="utf-8")

            # Create two different artifact folders producing the SAME packageName
            for i, ext in enumerate((ext1, ext2)):
                build = root / "apk-artifacts" / f"mod_{i}" / "build"
                for kind in ("apk", "jar"):
                    out = build / "outputs" / kind / "release"
                    out.mkdir(parents=True)
                    (out / f"tachiyomi-pt.geasscomics-v1.6.{5+i}.{kind}").write_bytes(b"data")
                info = json_format.MessageToDict(ext, preserving_proto_field_name=True)
                info["versionCode"] = int(info["versionCode"])
                info["contentWarning"] = ext.contentWarning
                info["module"] = "pt.geasscomics"
                info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in ext.sources]
                (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.object(Path, "home", return_value=root), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]):
                    with self.assertRaises(ValueError) as ctx:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    self.assertIn("DUPLICATE_PACKAGE_NAME=", str(ctx.exception))
            finally:
                os.chdir(previous)

    def test_idempotency_second_run_produces_zero_diff(self):
        ext = extension(slug="geasscomics", version=6)
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[extension(slug="geasscomics", version=5)]))
        old = [legacy(extension(slug="geasscomics", version=5))]

        with tempfile.TemporaryDirectory(prefix="nox-idempotency-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "pt.geasscomics" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-pt.geasscomics-v1.6.6.{kind}").write_bytes(b"fixed-asset")
            info = json_format.MessageToDict(ext, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = ext.contentWarning
            info["module"] = "pt.geasscomics"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in ext.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            calls = []
            def github(*args, **kwargs):
                calls.append(args)
                return '{"assets":[]}' if args[:2] == ("release", "view") and "assets" in args else ""

            previous = Path.cwd()
            try:
                os.chdir(root)
                # First publication run
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", side_effect=github), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]):
                    runpy.run_path(str(SCRIPT), run_name="__main__")

                # Snapshot of all catalog files
                index_json_1 = (root / "index.json").read_bytes()
                index_min_1 = (root / "index.min.json").read_bytes()
                index_pb_1 = (root / "index.pb").read_bytes()
                release_assets_1 = (root / "release-assets.json").read_bytes()
                index_html_1 = (root / "index.html").read_bytes()

                calls.clear()

                # Second publication run with the EXACT SAME artifacts
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", side_effect=github), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as error:
                        self.assertEqual(error.code, 0)

                # Verify byte-for-byte identity
                self.assertEqual((root / "index.json").read_bytes(), index_json_1)
                self.assertEqual((root / "index.min.json").read_bytes(), index_min_1)
                self.assertEqual((root / "index.pb").read_bytes(), index_pb_1)
                self.assertEqual((root / "release-assets.json").read_bytes(), release_assets_1)
                self.assertEqual((root / "index.html").read_bytes(), index_html_1)

                # Verify no new release uploads were triggered
                release_creates = [c for c in calls if c[:2] == ("release", "create")]
                self.assertEqual(len(release_creates), 0)

                # Verify zero duplicate packages in any catalog representation
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                pkgs = [e.packageName for e in current.extensionList.extensions]
                self.assertEqual(len(pkgs), len(set(pkgs)))
                self.assertEqual(len(pkgs), 1)

                legacy_entries = json.loads((root / "index.min.json").read_text())
                legacy_pkgs = [e["pkg"] for e in legacy_entries]
                self.assertEqual(len(legacy_pkgs), len(set(legacy_pkgs)))

                assets_data = json.loads((root / "release-assets.json").read_text())
                self.assertEqual(len(assets_data), 1)
            finally:
                os.chdir(previous)

    def test_downgrade_case_a_lower_version_code_fails_with_blocked_log(self):
        remote_ext = extension("geasscomics", 10)
        remote_ext.versionCode = 106010
        remote_ext.versionName = "1.6.10"
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[remote_ext]))
        old = [legacy(remote_ext)]

        incoming = index_pb2.Extension()
        incoming.CopyFrom(remote_ext)
        incoming.versionCode = 106009
        incoming.versionName = "1.6.9"

        with tempfile.TemporaryDirectory(prefix="nox-downgrade-a-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "pt.geasscomics" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-pt.geasscomics-v1.6.9.{kind}").write_bytes(b"incoming-asset")
            info = json_format.MessageToDict(incoming, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = incoming.contentWarning
            info["module"] = "pt.geasscomics"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in incoming.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    with self.assertRaises(ValueError) as ctx:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                output = stdout.getvalue()
                self.assertIn("VERSION_DOWNGRADE_BLOCKED=eu.kanade.tachiyomi.extension.pt.geasscomics", output)
                self.assertIn("REMOTE_VERSION_CODE=106010", output)
                self.assertIn("INCOMING_VERSION_CODE=106009", output)
                self.assertIn("VERSION_DOWNGRADE_BLOCKED", str(ctx.exception))
            finally:
                os.chdir(previous)

    def test_downgrade_case_b_equal_version_code_allowed(self):
        remote_ext = extension("geasscomics", 10)
        remote_ext.versionCode = 106010
        remote_ext.versionName = "1.6.10"
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[remote_ext]))
        old = [legacy(remote_ext)]

        incoming = index_pb2.Extension()
        incoming.CopyFrom(remote_ext)
        incoming.versionCode = 106010
        incoming.versionName = "1.6.10"

        with tempfile.TemporaryDirectory(prefix="nox-downgrade-b-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "pt.geasscomics" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-pt.geasscomics-v1.6.10.{kind}").write_bytes(b"incoming-asset")
            info = json_format.MessageToDict(incoming, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = incoming.contentWarning
            info["module"] = "pt.geasscomics"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in incoming.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertNotIn("VERSION_DOWNGRADE_BLOCKED", output)
                self.assertIn("PACKAGE_UNIQUENESS_CHECK=PASS", output)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                entries = [e for e in current.extensionList.extensions if e.packageName == PREFIX + "geasscomics"]
                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0].versionCode, 106010)
            finally:
                os.chdir(previous)

    def test_downgrade_case_c_higher_version_code_allowed(self):
        remote_ext = extension("geasscomics", 10)
        remote_ext.versionCode = 106010
        remote_ext.versionName = "1.6.10"
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[remote_ext]))
        old = [legacy(remote_ext)]

        incoming = index_pb2.Extension()
        incoming.CopyFrom(remote_ext)
        incoming.versionCode = 106011
        incoming.versionName = "1.6.11"

        with tempfile.TemporaryDirectory(prefix="nox-downgrade-c-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "pt.geasscomics" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-pt.geasscomics-v1.6.11.{kind}").write_bytes(b"incoming-asset")
            info = json_format.MessageToDict(incoming, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = incoming.contentWarning
            info["module"] = "pt.geasscomics"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in incoming.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertNotIn("VERSION_DOWNGRADE_BLOCKED", output)
                self.assertIn("PACKAGE_UNIQUENESS_CHECK=PASS", output)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                entries = [e for e in current.extensionList.extensions if e.packageName == PREFIX + "geasscomics"]
                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0].versionCode, 106011)
            finally:
                os.chdir(previous)

    def test_downgrade_case_d_new_package_allowed(self):
        remote_ext = extension("geasscomics", 10)
        remote_ext.versionCode = 106010
        remote_ext.versionName = "1.6.10"
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[remote_ext]))
        old = [legacy(remote_ext)]

        new_pkg = extension("karikari", 1)
        new_pkg.versionCode = 106001
        new_pkg.versionName = "1.6.1"

        with tempfile.TemporaryDirectory(prefix="nox-downgrade-d-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            build = root / "apk-artifacts" / "pt.karikari" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-pt.karikari-v1.6.1.{kind}").write_bytes(b"new-asset")
            info = json_format.MessageToDict(new_pkg, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = new_pkg.contentWarning
            info["module"] = "pt.karikari"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in new_pkg.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertNotIn("VERSION_DOWNGRADE_BLOCKED", output)
                self.assertIn("PACKAGE_UNIQUENESS_CHECK=PASS", output)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                all_pkgs = [e.packageName for e in current.extensionList.extensions]
                self.assertIn(PREFIX + "karikari", all_pkgs)
                self.assertIn(PREFIX + "geasscomics", all_pkgs)
                self.assertEqual(len(all_pkgs), len(set(all_pkgs)))
            finally:
                os.chdir(previous)

    def test_icon_resolution_own_icon(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publish_repo", SCRIPT)
        pub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pub)

        url = pub.get_icon_url("pt.geasscomics", None)
        self.assertEqual(url, f"{pub.ICON_BASE_URL}/src/pt/geasscomics/res/mipmap-xhdpi/ic_launcher.png")

    def test_icon_resolution_theme_icon(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publish_repo", SCRIPT)
        pub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pub)

        url = pub.get_icon_url("pt.samplewithmadara", "madara")
        self.assertEqual(url, f"{pub.ICON_BASE_URL}/lib-multisrc/madara/res/mipmap-xhdpi/ic_launcher.png")

    def test_icon_resolution_generic_nox_fallback(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publish_repo", SCRIPT)
        pub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pub)

        # yabai has no icon and no theme
        url_yabai = pub.get_icon_url("all.yabai", None)
        self.assertEqual(url_yabai, pub.DEFAULT_ICON_URL)
        # apenasumafa has theme zeistmanga which has no res/
        url_apenas = pub.get_icon_url("pt.apenasumafa", "zeistmanga")
        self.assertEqual(url_apenas, pub.DEFAULT_ICON_URL)

    def test_no_final_extension_has_empty_icon_url(self):
        # Package with no icon built as incoming artifact
        yabai_pkg = "eu.kanade.tachiyomi.extension.all.yabai"
        ext_yabai = index_pb2.Extension(
            name="Yabai",
            packageName=yabai_pkg,
            extensionLib="1.6",
            versionCode=106000,
            versionName="1.6.0",
            contentWarning=index_pb2.CONTENT_WARNING_NSFW,
            sources=[index_pb2.Source(id=1928970119194827182, name="Yabai", language="all", homeUrl="https://yabai.si")],
        )

        with tempfile.TemporaryDirectory(prefix="nox-no-icon-") as directory:
            root = Path(directory)
            (root / "index.json").write_text('{"extensionList": {"extensions": []}}', encoding="utf-8")
            (root / "index.min.json").write_text("[]", encoding="utf-8")

            build = root / "apk-artifacts" / "all.yabai" / "build"
            for kind in ("apk", "jar"):
                out = build / "outputs" / kind / "release"
                out.mkdir(parents=True)
                (out / f"tachiyomi-all.yabai-v1.6.0.{kind}").write_bytes(b"yabai-asset")
            info = json_format.MessageToDict(ext_yabai, preserving_proto_field_name=True)
            info["versionCode"] = int(info["versionCode"])
            info["contentWarning"] = ext_yabai.contentWarning
            info["module"] = "all.yabai"
            info["sources"] = [{"id": s.id, "name": s.name, "lang": s.language, "baseUrl": s.homeUrl} for s in ext_yabai.sources]
            (build / "keiyoushi-source-info.json").write_text(json.dumps(info), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT.resolve()), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT.resolve()), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertIn("ICON_URL_VALIDATION=PASS", output)
                self.assertIn("EXTENSIONS_WITH_MISSING_ICON_URL=0", output)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                self.assertEqual(len(current.extensionList.extensions), 1)
                final_yabai = current.extensionList.extensions[0]
                self.assertTrue(len(final_yabai.resources.iconUrl) > 0)
                self.assertTrue(final_yabai.resources.iconUrl.startswith("https://"))
            finally:
                os.chdir(previous)

    def test_publisher_fails_closed_if_icon_url_missing(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publish_repo", SCRIPT)
        pub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pub)

        bad_ext = extension("geasscomics", 1)
        bad_ext.resources.iconUrl = ""

        with self.assertRaises(ValueError) as ctx:
            pub.validate_catalog_integrity([bad_ext])
        self.assertIn("ICON_URL_VALIDATION=FAIL", str(ctx.exception))

    def test_publisher_fails_closed_if_empty_package_name(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("publish_repo", SCRIPT)
        pub = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pub)

        bad_ext = extension("geasscomics", 1)
        bad_ext.packageName = ""

        with self.assertRaises(ValueError) as ctx:
            pub.validate_catalog_integrity([bad_ext])
        self.assertIn("EMPTY_PACKAGE_NAME_DETECTED", str(ctx.exception))

    def test_json_serialization_contains_icon_url_for_all_extensions(self):
        # Create 2 extensions (one with own icon, one with fallback)
        geass = extension("geasscomics", 1)
        geass.versionCode = 106001
        geass.versionName = "1.6.1"
        geass.resources.iconUrl = "https://example.org/geass.png"

        yabai = index_pb2.Extension(
            name="Yabai",
            packageName="eu.kanade.tachiyomi.extension.all.yabai",
            extensionLib="1.6",
            versionCode=106000,
            versionName="1.6.0",
            contentWarning=index_pb2.CONTENT_WARNING_NSFW,
            resources=index_pb2.Resources(iconUrl=""),
            sources=[index_pb2.Source(id=1928970119194827182, name="Yabai", language="all", homeUrl="https://yabai.si")],
        )

        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[geass, yabai]))
        old = [legacy(geass), legacy(yabai)]

        with tempfile.TemporaryDirectory(prefix="nox-json-check-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT.resolve()), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT.resolve()), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertIn("ICON_URL_VALIDATION=PASS", output)

                raw_json = json.loads((root / "index.json").read_text(encoding="utf-8"))
                for ext in raw_json["extensionList"]["extensions"]:
                    self.assertIn("resources", ext)
                    self.assertIn("iconUrl", ext["resources"])
                    self.assertTrue(ext["resources"]["iconUrl"].startswith("https://"))
            finally:
                os.chdir(previous)

    def test_catalog_multiple_extensions_no_duplicate_package_name(self):
        ext1 = extension("geasscomics", 1)
        ext1.versionCode = 106001
        ext1.versionName = "1.6.1"

        ext2 = extension("karikari", 1)
        ext2.versionCode = 106001
        ext2.versionName = "1.6.1"

        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[ext1, ext2]))
        old = [legacy(ext1), legacy(ext2)]

        with tempfile.TemporaryDirectory(prefix="nox-multi-check-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                stdout = io.StringIO()
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT.resolve()), "[]", "fixture-sha"]), patch("sys.stdout", stdout):
                    try:
                        runpy.run_path(str(SCRIPT.resolve()), run_name="__main__")
                    except SystemExit as e:
                        self.assertEqual(e.code, 0)
                output = stdout.getvalue()
                self.assertIn("PACKAGE_UNIQUENESS_CHECK=PASS", output)
                self.assertIn("CATALOG_STRUCTURAL_VALIDATION=PASS", output)
                self.assertIn("DUPLICATE_PACKAGE_COUNT=0", output)
                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                pkgs = [e.packageName for e in current.extensionList.extensions]
                self.assertEqual(len(pkgs), 2)
                self.assertEqual(len(pkgs), len(set(pkgs)))
            finally:
                os.chdir(previous)

    def test_remote_catalog_with_duplicates_is_deduplicated_by_highest_version(self):
        # Create remote index with 3 duplicate instances of koharu
        v0 = extension("geasscomics", 1)
        v0.versionCode = 106001
        v0.versionName = "1.6.1"

        v1 = extension("geasscomics", 2)
        v1.versionCode = 106002
        v1.versionName = "1.6.2"

        v2 = extension("geasscomics", 3)
        v2.versionCode = 106003
        v2.versionName = "1.6.3"

        # Modern index contains all 3 copies
        modern = index_pb2.Index(extensionList=index_pb2.ExtensionList(extensions=[v0, v2, v1]))
        old = [legacy(v2)]

        with tempfile.TemporaryDirectory(prefix="nox-dedup-") as directory:
            root = Path(directory)
            (root / "index.json").write_text(json_format.MessageToJson(modern), encoding="utf-8")
            (root / "index.min.json").write_text(json.dumps(old), encoding="utf-8")

            previous = Path.cwd()
            try:
                os.chdir(root)
                # Run publisher with no new artifacts
                with patch.object(Path, "home", return_value=root), patch.object(github_utils, "run_gh", return_value='{"assets":[]}'), patch("sys.argv", [str(SCRIPT), "[]", "fixture-sha"]):
                    try:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
                    except SystemExit as error:
                        self.assertEqual(error.code, 0)

                current = json_format.Parse((root / "index.json").read_text(), index_pb2.Index())
                geass_entries = [e for e in current.extensionList.extensions if e.packageName == PREFIX + "geasscomics"]
                self.assertEqual(len(geass_entries), 1)
                self.assertEqual(geass_entries[0].versionCode, 106003)
                self.assertEqual(geass_entries[0].versionName, "1.6.3")
            finally:
                os.chdir(previous)

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
