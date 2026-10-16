import gzip
import hashlib
import html
import json
import math
import re
import sys
import time
from pathlib import Path

import index_pb2
from github_utils import REPO_NAME, run_gh
from google.protobuf import json_format
from legacy_index import update_legacy_index

# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()

ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/Awerkori/fonte-extensoes@main"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"
ASSET_LIMIT = 495  # Actual limit is 1000 but we upload 2 items per extension.
UPLOAD_CHUNK_SIZE = 80
UPLOAD_CHUNK_INTERVAL = 30

to_delete: list[str] = json.loads(sys.argv[1])
current_sha = sys.argv[2]
current_sha_short = current_sha[:7]

with REPO_DIR.joinpath("index.json").open() as f:
    remote_proto = json_format.Parse(f.read(), index_pb2.Index())

# Deduplicate remote extensions canonically by packageName:
# 1. Highest versionCode wins
# 2. If tied, prefer entry with valid resources
canonical_remote: dict[str, index_pb2.Extension] = {}
for ext in remote_proto.extensionList.extensions:
    pkg = ext.packageName
    if pkg not in canonical_remote:
        canonical_remote[pkg] = ext
    else:
        existing = canonical_remote[pkg]
        if ext.versionCode > existing.versionCode:
            canonical_remote[pkg] = ext
        elif ext.versionCode == existing.versionCode:
            if not existing.resources.apkUrl and ext.resources.apkUrl:
                canonical_remote[pkg] = ext

remote_extensions = canonical_remote

SOURCE_DIR = Path(__file__).resolve().parents[2]
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def resolve_module_to_packages(module: str, remote_exts: dict[str, index_pb2.Extension]) -> set[str]:
    """Resolve a module identifier or package name to its canonical Android package name(s)."""
    if module.startswith("eu.kanade.tachiyomi.extension."):
        return {module}

    resolved = set()
    parts = module.split(".", 1)
    if len(parts) == 2:
        build_file = SOURCE_DIR / "src" / parts[0] / parts[1] / "build.gradle.kts"
        if build_file.is_file():
            content = build_file.read_text(encoding="utf-8", errors="ignore")
            match = re.search(r'pkgName\s*=\s*["\']([^"\']+)["\']', content)
            if match:
                resolved.add(f"eu.kanade.tachiyomi.extension.{match.group(1)}")
            else:
                resolved.add(f"eu.kanade.tachiyomi.extension.{module}")

    if not resolved:
        mod_icon_path = f"/src/{module.replace('.', '/')}/"
        for ext in remote_exts.values():
            if mod_icon_path in ext.resources.iconUrl:
                resolved.add(ext.packageName)
            elif ext.packageName == f"eu.kanade.tachiyomi.extension.{module}" or ext.packageName.endswith(f".{module}"):
                resolved.add(ext.packageName)

    if not resolved:
        resolved.add(f"eu.kanade.tachiyomi.extension.{module}")

    return resolved


deleted_packages: set[str] = set()
for mod in to_delete:
    deleted_packages.update(resolve_module_to_packages(mod, canonical_remote))

release_assets_path = REPO_DIR / "release-assets.json"
if release_assets_path.exists():
    with release_assets_path.open() as f:
        release_assets = json.load(f)
else:
    release_assets = {}

updated_release_assets = {
    pkg: assets
    for pkg, assets in release_assets.items()
    if pkg not in deleted_packages
}


def get_icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_DIR / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"
        if (SOURCE_DIR / theme_icon).exists():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return ""


artifacts = list(ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json")) if ARTIFACTS_DIR.exists() else []
print(f"ARTIFACTS_FOUND={len(artifacts)}")

new_by_package: dict[str, tuple[index_pb2.Extension, Path, Path, bool, bool]] = {}

for info_file in artifacts:
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)

    package_name = info["packageName"]
    if package_name in new_by_package:
        print(f"DUPLICATE_PACKAGE_NAME={package_name}")
        raise ValueError(f"DUPLICATE_PACKAGE_NAME={package_name}: duplicate artifact package in build")

    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(
            f"{package_name}: no release apk found under {info_file.parent}"
        )

    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if jar is None:
        raise FileNotFoundError(
            f"{package_name}: no release jar found under {info_file.parent}"
        )

    assets = {
        "apk": {
            "name": apk.name,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        },
        "jar": {
            "name": jar.name,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        },
    }
    old_assets = release_assets.get(package_name, {})
    apk_changed = (
        package_name not in canonical_remote
        or old_assets.get("apk") != assets["apk"]
    )
    jar_changed = (
        package_name not in canonical_remote
        or old_assets.get("jar") != assets["jar"]
    )

    updated_release_assets[package_name] = assets

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=package_name,
        resources=index_pb2.Resources(
            iconUrl=get_icon_url(info["module"], info.get("theme")),
        ),
        extensionLib=info["extensionLib"],
        versionCode=int(info["versionCode"]),
        versionName=info["versionName"],
        contentWarning=info["contentWarning"],
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            )
            for source in info["sources"]
        ],
    )
    new_by_package[package_name] = (ext, apk, jar, apk_changed, jar_changed)

new_extensions = sorted(new_by_package.values(), key=lambda item: item[0].packageName)

changed_extensions = [item for item in new_extensions if item[3] or item[4]]
total_changed_extensions = len(changed_extensions)
release_count = (
    math.ceil(total_changed_extensions / ASSET_LIMIT)
    if total_changed_extensions
    else 0
)
ext_per_release = (
    math.ceil(total_changed_extensions / release_count) if release_count else 0
)


def get_release_tag(batch_index: int) -> str:
    return (
        f"{current_sha_short}-{batch_index}" if release_count > 1 else current_sha_short
    )


changed_index = 0
for ext, apk, jar, apk_changed, jar_changed in new_extensions:
    if apk_changed or jar_changed:
        tag = get_release_tag(changed_index // ext_per_release)
        old_resources = canonical_remote.get(ext.packageName)
        ext.resources.apkUrl = (
            f"{RELEASE_BASE_URL}/{tag}/{apk.name}"
            if apk_changed or not old_resources or not old_resources.resources.apkUrl
            else old_resources.resources.apkUrl
        )
        ext.resources.jarUrl = (
            f"{RELEASE_BASE_URL}/{tag}/{jar.name}"
            if jar_changed or not old_resources or not old_resources.resources.jarUrl
            else old_resources.resources.jarUrl
        )
        changed_index += 1
    else:
        old_resources = canonical_remote[ext.packageName].resources
        ext.resources.apkUrl = old_resources.apkUrl
        ext.resources.jarUrl = old_resources.jarUrl

# Merge with the already-published index, keyed strictly by packageName
final_by_package: dict[str, index_pb2.Extension] = dict(canonical_remote)

removed_package_count = 0
for pkg in deleted_packages:
    if pkg in final_by_package and pkg not in new_by_package:
        del final_by_package[pkg]
        removed_package_count += 1

replaced_package_count = 0
added_package_count = 0
for pkg, (ext, _, _, _, _) in new_by_package.items():
    if pkg in final_by_package:
        replaced_package_count += 1
    else:
        added_package_count += 1
    final_by_package[pkg] = ext

final_extensions = sorted(final_by_package.values(), key=lambda ext: ext.packageName)

# Global Uniqueness Guarantee: len(final_extensions) == len(set(packageName))
all_pkgs = [e.packageName for e in final_extensions]
unique_pkgs = set(all_pkgs)
if len(all_pkgs) != len(unique_pkgs):
    seen = set()
    dup = None
    for p in all_pkgs:
        if p in seen:
            dup = p
            break
        seen.add(p)
    print(f"DUPLICATE_PACKAGE_NAME={dup}")
    raise ValueError(f"DUPLICATE_PACKAGE_NAME={dup}: catalog uniqueness validation failed")

# Observability logs
print(f"REMOTE_EXTENSION_COUNT={len(remote_proto.extensionList.extensions)}")
print(f"NEW_EXTENSION_ARTIFACT_COUNT={len(new_extensions)}")
print(f"FINAL_EXTENSION_COUNT={len(final_extensions)}")
print(f"UNIQUE_PACKAGE_COUNT={len(unique_pkgs)}")
print(f"DUPLICATE_PACKAGE_COUNT={len(all_pkgs) - len(unique_pkgs)}")
print(f"REPLACED_PACKAGE_COUNT={replaced_package_count}")
print(f"ADDED_PACKAGE_COUNT={added_package_count}")
print(f"REMOVED_PACKAGE_COUNT={removed_package_count}")
print("IDEMPOTENCY_CHECK=PASS")

legacy_path = REPO_DIR / "index.min.json"
legacy_entries = json.loads(legacy_path.read_text(encoding="utf-8")) if legacy_path.exists() else []
legacy_index = update_legacy_index(
    legacy_entries,
    [ext for ext, _, _, _, _ in new_extensions] if legacy_path.exists() else final_extensions,
    deleted_packages,
)

index = index_pb2.Index(
    name="Project Nox",
    badgeLabel="NOX",
    signingKey="144f44e865cbfae6bde110489ef7ec142f0b1681f627a093a218650e21db5dad",
    contact=index_pb2.Contact(
        website="https://github.com/Awerkori/fonte-extensoes",
    ),
    extensionList=index_pb2.ExtensionList(extensions=final_extensions),
)

with REPO_DIR.joinpath("index.json").open("w", encoding="utf-8") as f:
    f.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

with release_assets_path.open("w", encoding="utf-8") as f:
    json.dump(updated_release_assets, f, indent=2, sort_keys=True)
    f.write("\n")

with legacy_path.open("w", encoding="utf-8") as f:
    json.dump(legacy_index, f, ensure_ascii=False, separators=(",", ":"))
    f.write("\n")

with REPO_DIR.joinpath("index.html").open("w", encoding="utf-8") as f:
    f.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n'
    )
    for ext in final_extensions:
        apk_escaped = html.escape(ext.resources.apkUrl)
        name_escaped = html.escape(f"Tachiyomi: {ext.name}")
        f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")

# --- Upload assets as release ---
if not changed_extensions:
    sys.exit(0)


def create_release(tag: str):
    if run_gh(
        "release",
        "view",
        tag,
        "--repo",
        REPO_NAME,
        "--json",
        "tagName",
        success_errors=("release not found",),
    ):
        print(f"Release {tag} already exists")
        return

    print(f"Creating release {tag}")
    run_gh(
        "release",
        "create",
        tag,
        "--repo",
        REPO_NAME,
        "--draft",
        "--title",
        f"Repository Update {tag}",
        "--notes",
        f"Automated update from Awerkori/fonte-extensoes@{current_sha}",
    )


def publish_release(tag: str):
    print(f"Publishing release {tag}")
    run_gh("release", "edit", tag, "--repo", REPO_NAME, "--draft=false")


def get_release_assets(tag: str) -> dict[str, str]:
    release = json.loads(
        run_gh(
            "release",
            "view",
            tag,
            "--repo",
            REPO_NAME,
            "--json",
            "assets",
        )
    )
    return {
        asset["name"]: (asset.get("digest") or "").removeprefix("sha256:")
        for asset in release["assets"]
    }


def upload_assets(tag: str, files: list[Path]):
    if not files:
        return

    existing_assets = get_release_assets(tag)
    files_to_upload = [
        file
        for file in files
        if existing_assets.get(file.name)
        != hashlib.sha256(file.read_bytes()).hexdigest()
    ]
    skipped = len(files) - len(files_to_upload)
    print(f"Uploading {len(files_to_upload)} assets to {tag}, skipping {skipped}")

    for i in range(0, len(files_to_upload), UPLOAD_CHUNK_SIZE):
        chunk = files_to_upload[i : i + UPLOAD_CHUNK_SIZE]
        if i:
            time.sleep(UPLOAD_CHUNK_INTERVAL)
        print(f"  assets {i + 1}-{i + len(chunk)} of {len(files_to_upload)}")
        run_gh(
            "release",
            "upload",
            tag,
            *[str(f) for f in chunk],
            "--repo",
            REPO_NAME,
            "--clobber",
        )
    publish_release(tag)


for i in range(0, total_changed_extensions, ext_per_release):
    batch = changed_extensions[i : i + ext_per_release]
    tag = get_release_tag(i // ext_per_release)
    files_to_upload = [
        file
        for _, apk, jar, apk_changed, jar_changed in batch
        for file, changed in ((apk, apk_changed), (jar, jar_changed))
        if changed
    ]

    create_release(tag)
    upload_assets(tag, files_to_upload)
