import gzip
import hashlib
import html
import json
import math
import subprocess
import sys
import time
from pathlib import Path

import index_pb2
from google.protobuf import json_format

# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()

ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/Awerkori/fonte-extensoes@main"
REPO_NAME = "Awerkori/extensoes"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"
ASSET_LIMIT = 495  # Actual limit is 1000 but we upload 2 items per extension.
RETRY_ATTEMPTS = 4
RETRY_BASE_DELAY = 60  # Documented minimum wait; doubles per attempt.
UPLOAD_CHUNK_SIZE = 80
UPLOAD_CHUNK_INTERVAL = 30

to_delete: list[str] = json.loads(sys.argv[1]) if len(sys.argv) > 1 else []
current_sha = sys.argv[2] if len(sys.argv) > 2 else "0000000000000000000000000000000000000000"
current_sha_short = current_sha[:7]

SOURCE_DIR = Path(__file__).resolve().parents[2]
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def pkg_to_module(pkg: str) -> str:
    return pkg.removeprefix("eu.kanade.tachiyomi.extension.")


def get_existing_modules() -> set[str]:
    modules = set()
    src_dir = SOURCE_DIR / "src"
    if not src_dir.is_dir():
        src_dir = REPO_DIR.parent / "main/src"
    if not src_dir.is_dir():
        return modules
    for lang_dir in src_dir.iterdir():
        if not lang_dir.is_dir():
            continue
        for ext_dir in lang_dir.iterdir():
            if ext_dir.is_dir() and (ext_dir / "build.gradle.kts").is_file():
                modules.add(f"{lang_dir.name}.{ext_dir.name}")
    return modules


def get_icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_DIR / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"
        if (SOURCE_DIR / theme_icon).exists():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


# Load current remote index
with REPO_DIR.joinpath("index.json").open() as f:
    remote_proto = json_format.Parse(f.read(), index_pb2.Index())

remote_extensions = {
    ext.packageName: ext for ext in remote_proto.extensionList.extensions
}
remote_modules = {
    pkg_to_module(ext.packageName): ext.packageName
    for ext in remote_proto.extensionList.extensions
}

# Load current release assets
release_assets_path = REPO_DIR / "release-assets.json"
if release_assets_path.exists():
    with release_assets_path.open() as f:
        release_assets = json.load(f)
else:
    release_assets = {}

# Build index entries for the freshly built apks.
new_extensions: list[tuple[index_pb2.Extension, Path, Path, bool]] = []
rebuilt_modules: set[str] = set()
skipped_downgrades: set[str] = set()

for info_file in ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    version_code = info["versionCode"]
    module = pkg_to_module(package_name)

    # SAFEGUARD: Downgrade protection
    remote_ext = remote_extensions.get(package_name)
    if remote_ext is not None and version_code < remote_ext.versionCode:
        skipped_downgrades.add(module)
        print(
            f"Skipping downgrade for {package_name}: "
            f"local code {version_code} < remote code {remote_ext.versionCode}",
            file=sys.stderr,
        )
        continue

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

    changed = (
        package_name not in remote_extensions
        or package_name not in release_assets
        or release_assets[package_name] != assets
    )

    rebuilt_modules.add(module)

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=package_name,
        resources=index_pb2.Resources(
            iconUrl=get_icon_url(info["module"], info.get("theme")),
        ),
        extensionLib=info["extensionLib"],
        versionCode=version_code,
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
    new_extensions.append((ext, apk, jar, changed))

# SAFEGUARD: Detect genuinely deleted modules (orphaned in remote index)
existing_modules = get_existing_modules()
genuinely_deleted = set(remote_modules.keys()) - existing_modules if existing_modules else set()
if genuinely_deleted:
    print(f"Removing genuinely deleted modules: {sorted(genuinely_deleted)}")

# SAFEGUARD: Safe deletion set.
# Only replace rebuilt modules or genuinely deleted modules.
# Never wipe unbuilt extensions when a core change triggers a broad to_delete list.
safe_to_delete = (rebuilt_modules | genuinely_deleted) - skipped_downgrades

print(
    f"Modules rebuilt: {len(rebuilt_modules)}, genuinely deleted: {len(genuinely_deleted)}, "
    f"safe to replace/delete: {len(safe_to_delete)}"
)

# Update release_assets map
updated_release_assets = {
    package_name: assets
    for package_name, assets in release_assets.items()
    if not any(package_name.endswith(f".{module}") for module in safe_to_delete)
}

for ext, apk, jar, _ in new_extensions:
    updated_release_assets[ext.packageName] = {
        "apk": {
            "name": apk.name,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        },
        "jar": {
            "name": jar.name,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        },
    }

new_extensions.sort(key=lambda item: item[0].packageName)

total_extensions = len(new_extensions)
release_count = math.ceil(total_extensions / ASSET_LIMIT) if total_extensions else 0
ext_per_release = math.ceil(total_extensions / release_count) if release_count else 0


def get_release_tag(batch_index: int) -> str:
    return (
        f"{current_sha_short}-{batch_index}" if release_count > 1 else current_sha_short
    )


for i, (ext, apk, jar, changed) in enumerate(new_extensions):
    if changed:
        tag = get_release_tag(i // ext_per_release)
        ext.resources.apkUrl = f"{RELEASE_BASE_URL}/{tag}/{apk.name}"
        ext.resources.jarUrl = f"{RELEASE_BASE_URL}/{tag}/{jar.name}"
    else:
        old_resources = remote_extensions[ext.packageName].resources
        ext.resources.apkUrl = old_resources.apkUrl
        ext.resources.jarUrl = old_resources.jarUrl

# Merge with the already-published index, safely dropping only rebuilt/deleted modules.
final_extensions = []
final_extensions.extend(
    ext
    for ext in remote_proto.extensionList.extensions
    if not any(ext.packageName.endswith(f".{module}") for module in safe_to_delete)
)
final_extensions.extend(ext for ext, _, _, _ in new_extensions)
final_extensions.sort(key=lambda ext: ext.packageName)

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

legacy_min_index = []
for ext in final_extensions:
    apk_filename = ext.resources.apkUrl.split("/")[-1]
    sources_list = []
    for s in ext.sources:
        sources_list.append({
            "name": s.name,
            "lang": s.language,
            "id": str(s.id),
            "baseUrl": s.homeUrl,
        })
    legacy_min_index.append({
        "name": ext.name,
        "pkg": ext.packageName,
        "apk": apk_filename,
        "lang": ext.sources[0].language if ext.sources else "all",
        "code": ext.versionCode,
        "version": ext.versionName,
        "nsfw": 1 if ext.contentWarning == index_pb2.CONTENT_WARNING_NSFW else 0,
        "sources": sources_list,
    })

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_min_index, f, separators=(",", ":"))

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

with release_assets_path.open("w", encoding="utf-8") as f:
    json.dump(updated_release_assets, f, indent=2, sort_keys=True)
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
if not new_extensions:
    sys.exit(0)


def run_gh(*args: str, success_errors: tuple[str, ...] = ()) -> str:
    delay = RETRY_BASE_DELAY
    for attempt in range(1, RETRY_ATTEMPTS + 1):
        result = subprocess.run(
            ["gh", *args],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip()

        error = result.stderr.lower()

        # The upload endpoint does not expose retry headers through gh, so use the
        # documented one-minute minimum with exponential backoff for secondary limits.
        # https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
        if "secondary rate limit" in error:
            if attempt < RETRY_ATTEMPTS:
                print(
                    f"secondary rate limit hit, retrying in {delay}s "
                    f"(attempt {attempt}/{RETRY_ATTEMPTS})",
                    file=sys.stderr,
                )
                time.sleep(delay)
                delay *= 2
                continue

        elif "api rate limit exceeded" in error and attempt < RETRY_ATTEMPTS:
            rate_limit = subprocess.run(
                ["gh", "api", "rate_limit", "--jq", ".resources.core.reset"],
                capture_output=True,
                text=True,
                check=False,
            )
            retry_delay = RETRY_BASE_DELAY
            if rate_limit.returncode == 0:
                retry_delay = max(
                    int(rate_limit.stdout.strip()) - int(time.time()) + 10,
                    RETRY_BASE_DELAY,
                )
            print(
                f"API rate limit hit, retrying in {retry_delay}s "
                f"(attempt {attempt}/{RETRY_ATTEMPTS})",
                file=sys.stderr,
            )
            time.sleep(retry_delay)
            continue

        elif any(success_error in error for success_error in success_errors):
            return result.stdout.strip()

        print(f"gh {' '.join(args)} failed: {result.stderr}", file=sys.stderr)
        sys.exit(result.returncode)


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


for i in range(0, total_extensions, ext_per_release):
    batch = new_extensions[i : i + ext_per_release]
    tag = get_release_tag(i // ext_per_release)
    files_to_upload = []
    for ext, apk, jar, changed in batch:
        if changed:
            files_to_upload.extend([apk, jar])

    if not files_to_upload:
        print(f"Nothing changed for {tag}, skipping release")
        continue

    create_release(tag)
    upload_assets(tag, files_to_upload)
