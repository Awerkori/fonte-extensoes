import gzip
import html
import json
import re
import sys
from pathlib import Path

from google.protobuf import json_format

import index_pb2

LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")

def pkg_to_module(pkg: str) -> str:
    return pkg.replace("eu.kanade.tachiyomi.extension.", "")

# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()
REPO_APK_DIR = REPO_DIR / "apk"
REPO_JAR_DIR = REPO_DIR / "jar"
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
REPO_JAR_DIR.mkdir(parents=True, exist_ok=True)

APK_BASE_URL = "https://raw.githubusercontent.com/FelipeGFA/extensoes/refs/heads/repo/apk"
JAR_BASE_URL = "https://raw.githubusercontent.com/FelipeGFA/extensoes/refs/heads/repo/jar"
ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/FelipeGFA/fonte-extensoes@main"

SOURCE_DIR = Path(__file__).resolve().parents[2]
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def get_icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_DIR / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"
        if (SOURCE_DIR / theme_icon).exists():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


def get_existing_modules() -> set[str]:
    modules = set()
    src_dir = REPO_DIR.parent.joinpath("main/src")
    if not src_dir.is_dir():
        return modules
    for lang_dir in src_dir.iterdir():
        if not lang_dir.is_dir():
            continue
        for ext_dir in lang_dir.iterdir():
            if ext_dir.is_dir():
                modules.add(f"{lang_dir.name}.{ext_dir.name}")
    return modules


to_delete: list[str] = json.loads(sys.argv[1])

# Merge with the already-published index, dropping the deleted/rebuilt modules.
with REPO_DIR.joinpath("index.json").open() as f:
    remote_proto = json_format.Parse(f.read(), index_pb2.Index())

remote_modules = {pkg_to_module(ext.packageName) for ext in remote_proto.extensionList.extensions}
remote_by_pkg = {ext.packageName: ext for ext in remote_proto.extensionList.extensions}
existing_modules = get_existing_modules()

new_extensions: list[index_pb2.Extension] = []
rebuilt_modules = set()
skipped_downgrades = set()

# Pass 1: Identify all rebuilt_modules and skipped_downgrades
for info_file in ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    version_code = info["versionCode"]
    module = pkg_to_module(package_name)

    remote_ext = remote_by_pkg.get(package_name)
    if remote_ext is not None and version_code < remote_ext.versionCode:
        skipped_downgrades.add(module)
        print(f"Skipping downgrade for {module}: local code {version_code} < remote code {remote_ext.versionCode}")
        continue
    
    rebuilt_modules.add(module)

# Detect modules that exist in the remote index but have been genuinely deleted
# from the source tree (not just modules that failed to build).
genuinely_deleted = remote_modules - existing_modules
if genuinely_deleted:
    print(f"Removing genuinely deleted modules not found in source tree: {sorted(genuinely_deleted)}")

safe_to_delete = (rebuilt_modules | genuinely_deleted) - skipped_downgrades

print(f"Modules rebuilt: {len(rebuilt_modules)}, genuinely deleted: {len(genuinely_deleted)}, safe to delete from old index: {len(safe_to_delete)}")

# Drop apks/jars for modules that were deleted or rebuilt (rebuilt ones are re-added below).
for module in safe_to_delete:
    for file in REPO_APK_DIR.glob(f"tachiyomi-{module}-v*.*.*.apk"):
        print(f"removing {file.name}")
        file.unlink(missing_ok=True)
    for file in REPO_JAR_DIR.glob(f"tachiyomi-{module}-v*.*.*.jar"):
        print(f"removing {file.name}")
        file.unlink(missing_ok=True)

# Pass 2: Actually process and extract the rebuilt modules
for info_file in ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    version_code = info["versionCode"]
    module = pkg_to_module(package_name)

    if module in skipped_downgrades:
        continue

    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(f"{package_name}: no release apk found under {info_file.parent}")

    apk_name = apk.name.replace("-release.apk", ".apk")
    (REPO_APK_DIR / apk_name).write_bytes(apk.read_bytes())

    jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
    if jar is None:
        raise FileNotFoundError(f"{package_name}: no release jar found under {info_file.parent}")
    (REPO_JAR_DIR / jar.name).write_bytes(jar.read_bytes())

    new_extensions.append(
        index_pb2.Extension(
            name=info["name"],
            packageName=package_name,
            resources=index_pb2.Resources(
                apkUrl=f"{APK_BASE_URL}/{apk_name}",
                jarUrl=f"{JAR_BASE_URL}/{jar.name}",
                iconUrl=get_icon_url(info["module"], info.get("theme")),
            ),
            extensionLib=info["extensionLib"],
            versionCode=info["versionCode"],
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
    )


all_extensions = [
    ext
    for ext in remote_proto.extensionList.extensions
    if not any(ext.packageName.endswith(f".{module}") for module in safe_to_delete)
]
all_extensions.extend(new_extensions)
all_extensions.sort(key=lambda ext: ext.packageName)

index = index_pb2.Index(
    name="FelipeGFA",
    badgeLabel="FGFA",
    signingKey="9bf5754a79fd686cb77e6335a2a9e5351ad73ca824c27b09e7b3437f806fea77",
    contact=index_pb2.Contact(
        website="https://felipegfa.github.io", discord="https://discord.gg/QpyjwsWENq"
    ),
    extensionList=index_pb2.ExtensionList(extensions=all_extensions),
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
    f.write(gzip.compress(index.SerializeToString()))


def get_legacy_lang(ext) -> str:
    apk_filename = ext.resources.apkUrl.split("/")[-1]
    lang = LANGUAGE_REGEX.search(apk_filename).group(1)
    if len(ext.sources) == 1:
        source_language = ext.sources[0].language
        if (
            source_language != lang
            and source_language not in {"all", "other"}
            and lang not in {"all", "other"}
        ):
            lang = source_language
    return lang


legacy_json_index = [
    {
        "name": f"Tachiyomi: {ext.name}",
        "pkg": ext.packageName,
        "apk": ext.resources.apkUrl.split("/")[-1],
        "lang": get_legacy_lang(ext),
        "code": ext.versionCode,
        "version": ext.versionName,
        "nsfw": 1 if ext.contentWarning > 2 else 0,
        "sources": [
            {
                "name": source.name,
                "lang": source.language,
                "id": str(source.id),
                "baseUrl": source.homeUrl,
            }
            for source in ext.sources
        ],
    }
    for ext in all_extensions
]

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_json_index, f, ensure_ascii=False, separators=(",", ":"))

with REPO_DIR.joinpath("index.html").open("w", encoding="utf-8") as f:
    f.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n'
    )
    for ext in all_extensions:
        apk_escaped = html.escape(ext.resources.apkUrl.split("/")[-1])
        name_escaped = html.escape(f"Tachiyomi: {ext.name}")
        f.write(f'<a href="apk/{apk_escaped}">{name_escaped}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")
