import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/keiyoushi/extensions-source.git"
UPSTREAM_BRANCH = "main"
SYNC_BRANCH = "sync"
DEFERRED_MIGRATIONS_FILE = Path(".github/sync-deferred-migrations.json")


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
    )

    if check and result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)

    return result.stdout


def ensure_upstream_remote() -> None:
    if subprocess.run(
        ["git", "remote", "get-url", UPSTREAM_REMOTE],
        capture_output=True,
    ).returncode != 0:
        git("remote", "add", UPSTREAM_REMOTE, UPSTREAM_URL)


def ensure_clean_tree() -> None:
    if git("status", "--porcelain").strip():
        print("Working tree is not clean")
        sys.exit(1)


def parse_name_status(output: str) -> list[tuple[str, list[str]]]:
    tokens = output.rstrip("\0").split("\0")
    entries = []
    i = 0

    while i < len(tokens) and tokens[i]:
        status = tokens[i]
        i += 1
        path_count = 2 if status[0] in {"R", "C"} else 1
        entries.append((status, tokens[i:i + path_count]))
        i += path_count

    return entries


def is_preserved(path: str) -> bool:
    return path == ".github" or path.startswith(".github/")


def sync_unit(path: str) -> str | None:
    if is_preserved(path):
        return None

    parts = path.split("/")

    if len(parts) >= 3 and parts[0] == "src":
        return "/".join(parts[:3])

    if len(parts) >= 2 and parts[0] in {"lib", "lib-multisrc"}:
        return "/".join(parts[:2])

    return path


def changed_entries(base: str, ref: str) -> list[tuple[str, list[str]]]:
    output = git("diff", "--name-status", "--find-renames", "-z", base, ref)
    return parse_name_status(output)


def collect_units(entries: list[tuple[str, list[str]]]) -> tuple[list[str], list[str]]:
    units = set()
    preserved = set()

    for _, paths in entries:
        for path in paths:
            unit = sync_unit(path)

            if unit is None:
                preserved.add(path)
            else:
                units.add(unit)

    return sorted(units), sorted(preserved)


def path_exists(ref: str, path: str) -> bool:
    return subprocess.run(
        ["git", "cat-file", "-e", f"{ref}:{path}"],
        capture_output=True,
    ).returncode == 0


_VERSION_CODE_RE = re.compile(r"(versionCode\s*=\s*)(\d+)")
_THEME_RE = re.compile(r"""theme\s*=\s*["']([^"']+)["']""")
_LIB_VERSION_RE = re.compile(r"""libVersion\s*=\s*["']([^"']+)["']""")
_BASE_VERSION_CODE_RE = re.compile(r"baseVersionCode\s*=\s*(\d+)")


def _read_file_text(ref: str | None, path: str) -> str | None:
    if ref is None:
        p = Path(path)
        return p.read_text() if p.exists() else None
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        capture_output=True,
        text=True,
    )
    return result.stdout if result.returncode == 0 else None


def read_base_version_code(ref: str | None, theme: str) -> int:
    theme_gradle = f"lib-multisrc/{theme}/build.gradle.kts"
    content = _read_file_text(ref, theme_gradle)
    if not content:
        return 0
    m = _BASE_VERSION_CODE_RE.search(content)
    return int(m.group(1)) if m else 0


def effective_version_code(ref: str | None, unit: str) -> tuple[int, int, int] | None:
    """Read (raw_version, base_version, effective_version) for an extension unit."""
    gradle_path = f"{unit}/build.gradle.kts"
    content = _read_file_text(ref, gradle_path)
    if not content:
        return None

    v_match = _VERSION_CODE_RE.search(content)
    if not v_match:
        return None

    raw_vc = int(v_match.group(2))
    t_match = _THEME_RE.search(content)
    theme = t_match.group(1) if t_match else None
    base_vc = read_base_version_code(ref, theme) if theme else 0
    return raw_vc, base_vc, raw_vc + base_vc


def bump_version_code_if_needed(unit: str, upstream_ref: str) -> tuple[int, int, int, int, int, int, int] | None:
    """If upstream effective version >= local effective version, bump local raw versionCode.

    Returns (loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw) if bumped, None otherwise.
    Only rewrites the file on disk; caller must `git add` it.
    """
    local_info = effective_version_code(None, unit)
    upstream_info = effective_version_code(upstream_ref, unit)

    if local_info is None or upstream_info is None:
        return None

    loc_raw, loc_base, loc_eff = local_info
    up_raw, up_base, up_eff = upstream_info

    if up_eff < loc_eff:
        return None

    desired_effective = up_eff + 1
    new_raw = desired_effective - loc_base

    gradle_path = f"{unit}/build.gradle.kts"
    local_file = Path(gradle_path)
    local_text = local_file.read_text()
    new_text = _VERSION_CODE_RE.sub(lambda m: f"{m.group(1)}{new_raw}", local_text)
    local_file.write_text(new_text)

    return loc_raw, loc_base, loc_eff, up_raw, up_base, up_eff, new_raw


def structural_metadata(ref: str | None, unit: str) -> tuple[str | None, str | None] | None:
    """Read the multisrc selectors whose compatibility is independent of source code."""
    content = _read_file_text(ref, f"{unit}/build.gradle.kts")
    if content is None:
        return None
    theme = _THEME_RE.search(content)
    lib_version = _LIB_VERSION_RE.search(content)
    return (
        theme.group(1) if theme else None,
        lib_version.group(1) if lib_version else None,
    )


def multisrc_metadata(ref: str | None, theme: str) -> tuple[str | None, int] | None:
    """Read the compatibility and publication metadata for one multisrc theme."""
    content = _read_file_text(ref, f"lib-multisrc/{theme}/build.gradle.kts")
    if content is None:
        return None
    lib = _LIB_VERSION_RE.search(content)
    base = _BASE_VERSION_CODE_RE.search(content)
    return (lib.group(1) if lib else None, int(base.group(1)) if base else 0)


def dependency_map(ref: str | None) -> dict[str, list[str]]:
    """Map every selected multisrc theme to its extension units."""
    result: dict[str, list[str]] = {}
    paths = Path("src").glob("*/*/build.gradle.kts") if ref is None else ()
    if ref is None:
        for gradle in paths:
            metadata = structural_metadata(None, "/".join(gradle.parts[:3]))
            if metadata and metadata[0]:
                result.setdefault(metadata[0], []).append("/".join(gradle.parts[:3]))
    else:
        for path in git("ls-tree", "-r", "--name-only", ref, "src").splitlines():
            if not path.endswith("/build.gradle.kts"):
                continue
            unit = "/".join(path.split("/")[:3])
            metadata = structural_metadata(ref, unit)
            if metadata and metadata[0]:
                result.setdefault(metadata[0], []).append(unit)
    return {theme: sorted(set(units)) for theme, units in result.items()}


def load_deferred_migrations() -> dict[str, dict[str, object]]:
    if not DEFERRED_MIGRATIONS_FILE.exists():
        return {}
    data = json.loads(DEFERRED_MIGRATIONS_FILE.read_text())
    return data.get("themes", {})


def write_deferred_migrations(themes: dict[str, dict[str, object]]) -> bool:
    if not themes:
        if DEFERRED_MIGRATIONS_FILE.exists():
            DEFERRED_MIGRATIONS_FILE.unlink()
            return True
        return False
    DEFERRED_MIGRATIONS_FILE.parent.mkdir(parents=True, exist_ok=True)
    DEFERRED_MIGRATIONS_FILE.write_text(json.dumps({"themes": themes}, indent=2, sort_keys=True) + "\n")
    return True


def preflight_multisrc(
    base: str,
    upstream_ref: str,
    protected_units: list[str],
) -> tuple[dict[str, list[dict[str, object]]], set[str]]:
    """Plan all multisrc migrations before mutating the real worktree.

    A deferred theme is deliberately included even after its upstream commit became
    an ancestor of main.  This is what prevents an `-s ours` merge from losing it.
    """
    changed_themes = {
        unit.split("/", 1)[1]
        for unit in collect_units(changed_entries(base, upstream_ref))[0]
        if unit.startswith("lib-multisrc/")
    }
    # A source may switch themes even when no existing multisrc directory changed.
    # Include both selectors so its dependencies are recalculated before merging.
    for unit in collect_units(changed_entries(base, upstream_ref))[0]:
        if not unit.startswith("src/"):
            continue
        local = structural_metadata(None, unit)
        upstream = structural_metadata(upstream_ref, unit)
        if local != upstream:
            changed_themes.update(value[0] for value in (local, upstream) if value and value[0])
    deferred = load_deferred_migrations()
    changed_themes.update(deferred)
    local_deps = dependency_map(None)
    upstream_deps = dependency_map(upstream_ref)
    report: dict[str, list[dict[str, object]]] = {}
    blocked: set[str] = set()
    protected = set(protected_units)
    for entry in deferred.values():
        protected.update(str(unit) for unit in entry.get("units", []))
    for theme in sorted(changed_themes):
        local_multi = multisrc_metadata(None, theme)
        upstream_multi = multisrc_metadata(upstream_ref, theme)
        if local_multi == upstream_multi and theme not in deferred:
            continue
        dependents = sorted(set(local_deps.get(theme, [])) | set(upstream_deps.get(theme, [])))
        rows = []
        for unit in dependents:
            local = structural_metadata(None, unit)
            upstream = structural_metadata(upstream_ref, unit)
            local_lib = local[1] if local else None
            upstream_lib = upstream[1] if upstream else None
            new_lib = upstream_multi[0] if upstream_multi else None
            needs = bool(unit in protected and (local_lib != new_lib or (local and upstream and local[0] != upstream[0])))
            supported = bool(
                upstream_multi
                and upstream
                and upstream[0] == theme
                and upstream_lib == new_lib
            )
            source_changed = bool(unit in protected and subprocess.run(
                ["git", "diff", "--quiet", upstream_ref, "--", f"{unit}/src"],
            ).returncode != 0)
            rows.append({
                "unit": unit,
                "protected": unit in protected,
                "local_libVersion": local_lib,
                "upstream_libVersion": upstream_lib,
                "multisrc_libVersion": new_lib,
                "source_divergent": source_changed,
                "migration_required": needs,
                "migration_supported": supported,
            })
        report[theme] = rows
        if any(row["migration_required"] and not row["migration_supported"] for row in rows):
            blocked.add(theme)
    return report, blocked


def verify_migration_plan(
    report: dict[str, list[dict[str, object]]],
    upstream_ref: str,
) -> set[str]:
    """Compile protected selector migrations in a disposable worktree.

    This is intentionally before the real merge/restore/rm operations.  A failed
    candidate becomes deferred instead of leaving the real tree half migrated.
    """
    candidates = {
        theme: [str(row["unit"]) for row in rows if row["migration_required"]]
        for theme, rows in report.items()
    }
    candidates = {theme: units for theme, units in candidates.items() if units}
    if not candidates:
        return set()
    blocked: set[str] = set()
    root = Path.cwd()
    with tempfile.TemporaryDirectory(prefix="nox-sync-preflight-") as directory:
        sandbox = Path(directory) / "repo"
        git("worktree", "add", "--detach", str(sandbox), "HEAD")
        try:
            for theme, units in candidates.items():
                result = subprocess.run(
                    ["git", "-C", str(sandbox), "restore", f"--source={upstream_ref}", "--worktree", "--staged", "--", f"lib-multisrc/{theme}"],
                    text=True,
                )
                if result.returncode:
                    blocked.add(theme)
                    continue
                for unit in units:
                    local = sandbox / unit / "build.gradle.kts"
                    upstream = _read_file_text(upstream_ref, f"{unit}/build.gradle.kts")
                    if not local.exists() or upstream is None:
                        blocked.add(theme)
                        break
                    target = structural_metadata(upstream_ref, unit)
                    if target is None:
                        blocked.add(theme)
                        break
                    text = local.read_text()
                    text = _LIB_VERSION_RE.sub(f'libVersion = "{target[1]}"', text, count=1)
                    text = _THEME_RE.sub(f'theme = "{target[0]}"', text, count=1)
                    local.write_text(text)
                if theme in blocked:
                    continue
                # Metadata generation does not compile the protected source and
                # therefore cannot prove a libVersion API migration is safe.
                tasks = [f":{unit.replace('/', ':')}:assembleDebug" for unit in units]
                result = subprocess.run([str(sandbox / "gradlew"), *tasks], cwd=sandbox, text=True)
                if result.returncode:
                    blocked.add(theme)
        finally:
            os.chdir(root)
            git("worktree", "remove", "--force", str(sandbox), check=False)
    return blocked


def deferred_state(report: dict[str, list[dict[str, object]]], blocked: set[str], upstream_ref: str) -> dict[str, dict[str, object]]:
    """Persist only migrations that could not be proven safe; they are retried later."""
    state: dict[str, dict[str, object]] = {}
    for theme in blocked:
        state[theme] = {
            "upstream_ref": upstream_ref,
            "units": [row["unit"] for row in report.get(theme, []) if row["migration_required"]],
        }
    # A previously deferred theme that is not in this plan has become compatible
    # (or was deleted upstream), so it is intentionally removed after application.
    return state


def merge_structural_metadata(unit: str, upstream_ref: str) -> bool:
    """Adopt compatible upstream selectors while leaving Nox source/version fields intact."""
    local_path = Path(f"{unit}/build.gradle.kts")
    upstream = structural_metadata(upstream_ref, unit)
    if not local_path.exists() or upstream is None:
        return False

    upstream_theme, upstream_lib = upstream
    text = local_path.read_text()
    original = text

    def replace_or_insert(
        value: str | None,
        pattern: re.Pattern[str],
        key: str,
    ) -> None:
        nonlocal text
        if value is None:
            text = pattern.sub("", text)
            return
        replacement = f'{key} = "{value}"'
        if pattern.search(text):
            text = pattern.sub(replacement, text, count=1)
        else:
            marker = re.search(r"(?m)^(\s*)source\s*\{", text)
            if marker:
                indent = marker.group(1) + "    "
                text = text[:marker.start()] + f"{indent}{replacement}\n" + text[marker.start():]

    # A caller reaches this function only after pre-flight proved this migration
    # builds in an isolated worktree.  Keeping an old local selector here would
    # create the forbidden old-source/old-lib/new-multisrc hybrid.
    replace_or_insert(upstream_lib, _LIB_VERSION_RE, "libVersion")
    replace_or_insert(upstream_theme, _THEME_RE, "theme")
    if text == original:
        return False

    local_path.write_text(text)
    print(f"Structural metadata: {unit} -> theme={upstream_theme or 'none'}, libVersion={upstream_lib or 'none'}")
    return True


def bump_after_structural_change(unit: str, previous_info: tuple[int, int, int] | None) -> bool:
    """Keep a structural migration strictly newer than the pre-migration effective version."""
    if previous_info is None:
        return False
    current_info = effective_version_code(None, unit)
    if current_info is None or current_info[2] > previous_info[2]:
        return False

    raw_vc, base_vc, _ = current_info
    new_raw = previous_info[2] + 1 - base_vc
    if new_raw <= raw_vc:
        return False
    gradle_path = Path(f"{unit}/build.gradle.kts")
    text = gradle_path.read_text()
    gradle_path.write_text(_VERSION_CODE_RE.sub(lambda m: f"{m.group(1)}{new_raw}", text, count=1))
    print(f"Structural version guard: {unit} (versionCode {raw_vc} -> {new_raw})")
    return True


def validate_multisrc_compatibility() -> list[str]:
    """Return extension units whose selected multisrc and libVersion cannot build."""
    errors = []
    for gradle_path in sorted(Path("src").glob("*/*/build.gradle.kts")):
        unit = "/".join(gradle_path.parts[:3])
        content = gradle_path.read_text()
        theme_match = _THEME_RE.search(content)
        lib_match = _LIB_VERSION_RE.search(content)
        if not theme_match or not lib_match:
            continue

        theme = theme_match.group(1)
        extension_lib = lib_match.group(1)
        multisrc_path = Path(f"lib-multisrc/{theme}/build.gradle.kts")
        if not multisrc_path.exists():
            errors.append(f"{unit}: multisrc '{theme}' não existe")
            continue
        multisrc_match = _LIB_VERSION_RE.search(multisrc_path.read_text())
        multisrc_lib = multisrc_match.group(1) if multisrc_match else None
        if multisrc_lib != extension_lib:
            errors.append(
                f"{unit}: extensão libVersion {extension_lib} != multisrc {theme} {multisrc_lib or 'ausente'}",
            )
    return errors


def validate_affected_builds(units: list[str]) -> None:
    """Run the cheap source-info task for units whose structural metadata changed."""
    gradlew = Path("gradlew")
    if not units or not gradlew.exists():
        return
    tasks = [f":{unit.replace('/', ':')}:generateSourceInfo" for unit in units if unit.startswith("src/")]
    if not tasks:
        return
    print(f"Validating affected extensions: {', '.join(units)}")
    result = subprocess.run([f"./{gradlew.name}", *tasks], text=True)
    if result.returncode != 0:
        raise RuntimeError("Build validation failed for affected extensions")


def get_protected_nox_units(base: str, upstream_ref: str) -> list[str]:
    """Protected Nox extensions: src/<lang>/<ext> modified locally vs merge-base and existing in upstream."""
    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)
    protected = []
    for unit in sorted(main_units):
        if unit.startswith("src/") and path_exists(upstream_ref, f"{unit}/build.gradle.kts"):
            protected.append(unit)
    return protected


def update_sync_branch(upstream_ref: str, push: bool) -> None:
    if push:
        git("push", "origin", f"{upstream_ref}:refs/heads/{SYNC_BRANCH}")
    else:
        print(f"Would update origin/{SYNC_BRANCH} from {upstream_ref}")


def print_plan(
    base: str,
    upstream_ref: str,
    upstream_only_units: list[str],
    preserved_paths: list[str],
    main_only_units: list[str],
    conflict_units: list[str],
    protected_units: list[str],
) -> None:
    commits = git("rev-list", "--count", f"{base}..{upstream_ref}").strip()

    print(f"Base: {base}")
    print(f"Upstream commits: {commits}")
    print(f"Upstream units to apply: {len(upstream_only_units)}")

    for unit in upstream_only_units:
        print(f"  upstream: {unit}")

    if preserved_paths:
        print(f"Preserved .github paths: {len(preserved_paths)}")

    if main_only_units:
        print(f"Main-only units preserved: {len(main_only_units)}")

        for unit in main_only_units:
            print(f"  main: {unit}")

    if conflict_units:
        print(f"Conflict units (modified by both, Nox wins): {len(conflict_units)}")
        for unit in conflict_units:
            print(f"  conflict: {unit}")

    if protected_units:
        print(f"\nProtected Nox extensions: {len(protected_units)}")
        for unit in protected_units:
            loc_info = effective_version_code(None, unit)
            up_info = effective_version_code(upstream_ref, unit)
            if not loc_info or not up_info:
                continue
            loc_raw, loc_base, loc_eff = loc_info
            up_raw, up_base, up_eff = up_info
            print(f"{unit}:")
            print(f"  local raw: {loc_raw}")
            print(f"  local base: {loc_base}")
            print(f"  local effective: {loc_eff}")
            print(f"  upstream raw: {up_raw}")
            print(f"  upstream base: {up_base}")
            print(f"  upstream effective: {up_eff}")
            if up_eff >= loc_eff:
                desired_eff = up_eff + 1
                new_raw = desired_eff - loc_base
                print(f"  action: bump local raw -> {new_raw}")
            else:
                print(f"  action: keep (local effective ahead)")


def structural_conflicts(conflict_units: list[str], upstream_ref: str) -> list[str]:
    units = []
    for unit in conflict_units:
        local = structural_metadata(None, unit)
        upstream = structural_metadata(upstream_ref, unit)
        if local is not None and upstream is not None and local != upstream:
            units.append(unit)
    return units


def apply_units(
    upstream_ref: str,
    units: list[str],
    conflict_units: set[str],
    protected_units: list[str],
    structural_units: list[str],
    blocked_themes: set[str],
    deferred: dict[str, dict[str, object]],
) -> list[str]:
    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    # 1. Apply upstream units (except conflict units where Nox wins)
    for unit in units:
        if unit.startswith("lib-multisrc/") and unit.split("/", 1)[1] in blocked_themes:
            print(f"Deferred structural migration: {unit}")
            continue
        if unit in conflict_units and not unit.startswith("lib-multisrc/"):
            if unit in structural_units:
                previous_info = effective_version_code(None, unit)
                changed = merge_structural_metadata(unit, upstream_ref)
                version_changed = bump_after_structural_change(unit, previous_info) if changed else False
                if changed or version_changed:
                    git("add", "--", f"{unit}/build.gradle.kts")
            continue

        print(f"Applying {unit}")
        git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)

        if path_exists(upstream_ref, unit):
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

    # Deferred themes may no longer appear in base..upstream after an ours merge.
    # Their protected extensions must still receive their proven selector migration.
    for unit in structural_units:
        if unit in conflict_units:
            continue
        previous_info = effective_version_code(None, unit)
        changed = merge_structural_metadata(unit, upstream_ref)
        version_changed = bump_after_structural_change(unit, previous_info) if changed else False
        if changed or version_changed:
            git("add", "--", f"{unit}/build.gradle.kts")

    # 2. Version Guard on all protected Nox units
    bumped = []
    validation_units = set(structural_units)
    deferred_units = {str(unit) for entry in deferred.values() for unit in entry.get("units", [])}
    for unit in protected_units:
        if unit in deferred_units:
            print(f"Version guard: deferred {unit}; metadata and version preserved")
            continue
        res = bump_version_code_if_needed(unit, upstream_ref)
        if res is not None:
            loc_raw, _, _, _, _, _, new_raw = res
            git("add", "--", f"{unit}/build.gradle.kts")
            bumped.append(f"{unit} -> versionCode={new_raw}")
            validation_units.add(unit)
            print(f"Version guard: bumped {unit} (versionCode {loc_raw} -> {new_raw})")
        else:
            print(f"Version guard: {unit} already ahead")

    if write_deferred_migrations(deferred):
        git("add", "--", str(DEFERRED_MIGRATIONS_FILE))

    git("diff", "--check")

    errors = validate_multisrc_compatibility()
    if errors:
        print("Structural compatibility validation failed:")
        for error in errors:
            print(f"  - {error}")
        git("merge", "--abort", check=False)
        sys.exit(1)

    try:
        validate_affected_builds(sorted(validation_units))
    except RuntimeError as error:
        print(str(error))
        git("merge", "--abort", check=False)
        sys.exit(1)

    commit_msg = "Sync upstream"
    if bumped:
        commit_msg += "\n\nNox-resolved conflicts touched by upstream:\n" + "\n".join(f"  - {b}" for b in bumped)

    git("commit", "-m", commit_msg)
    return bumped


def _write_step_summary(protected_units: list[str], bumped: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path or not protected_units:
        return
    lines = ["\n## Nox Protected Extensions\n\n"]
    for unit in protected_units:
        tag = next((b for b in bumped if b.startswith(unit)), None)
        if tag:
            ver = tag.split("=")[1]
            lines.append(f"- `{unit}` (kept Nox code, bumped to `{ver}`)\n")
        else:
            lines.append(f"- `{unit}` (kept Nox code, version already ahead)\n")
    with open(summary_path, "a") as f:
        f.writelines(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--push", action="store_true")
    parser.add_argument("--apply-no-push", action="store_true")
    args = parser.parse_args()

    if sum((args.dry_run, args.push, args.apply_no_push)) > 1:
        print("Use only one of --dry-run, --push, or --apply-no-push")
        sys.exit(1)

    ensure_clean_tree()
    ensure_upstream_remote()

    git("fetch", "origin")
    git("fetch", UPSTREAM_REMOTE, UPSTREAM_BRANCH)

    upstream_ref = f"{UPSTREAM_REMOTE}/{UPSTREAM_BRANCH}"
    base = git("merge-base", "HEAD", upstream_ref).strip()

    upstream_entries = changed_entries(base, upstream_ref)
    upstream_units, preserved_paths = collect_units(upstream_entries)

    main_entries = changed_entries(base, "HEAD")
    main_units, _ = collect_units(main_entries)

    conflict_units = sorted(set(upstream_units) & set(main_units))
    conflict_set = set(conflict_units)
    main_only_units = sorted(set(main_units) - set(upstream_units))
    upstream_only_units = sorted(set(upstream_units) - conflict_set)
    protected_units = get_protected_nox_units(base, upstream_ref)
    structural_units = structural_conflicts(conflict_units, upstream_ref)
    for theme in load_deferred_migrations():
        unit = f"lib-multisrc/{theme}"
        if path_exists(upstream_ref, unit):
            upstream_units.append(unit)
    upstream_units = sorted(set(upstream_units))
    conflict_units = sorted(set(upstream_units) & set(main_units))
    conflict_set = set(conflict_units)
    main_only_units = sorted(set(main_units) - set(upstream_units))
    upstream_only_units = sorted(set(upstream_units) - conflict_set)
    preflight, preflight_blocked = preflight_multisrc(base, upstream_ref, protected_units)

    print_plan(
        base,
        upstream_ref,
        upstream_only_units,
        preserved_paths,
        main_only_units,
        conflict_units,
        protected_units,
    )
    if structural_units:
        print(f"Structural conflicts (metadata from upstream, Nox code preserved): {len(structural_units)}")
        for unit in structural_units:
            print(f"  structural: {unit}")

    if preflight:
        print("Multisrc pre-flight:")
        for theme, rows in preflight.items():
            local_multi = multisrc_metadata(None, theme)
            upstream_multi = multisrc_metadata(upstream_ref, theme)
            print(f"  lib-multisrc/{theme}: local={local_multi}, upstream={upstream_multi}")
            for row in rows:
                print(
                    "    {unit}: protected={protected}, local libVersion={local_libVersion}, "
                    "upstream libVersion={upstream_libVersion}, new multisrc={multisrc_libVersion}, "
                    "source divergent={source_divergent}, migration required={migration_required}, "
                    "migration supported={migration_supported}".format(**row),
                )

    if not upstream_units and not load_deferred_migrations():
        print("No upstream changes to apply")
        return

    if args.dry_run or not (args.push or args.apply_no_push):
        print("Dry run only; no changes were applied")
        return

    blocked = preflight_blocked | verify_migration_plan(
        {theme: rows for theme, rows in preflight.items() if theme not in preflight_blocked},
        upstream_ref,
    )
    deferred = deferred_state(preflight, blocked, upstream_ref)
    if blocked:
        print("Deferred migrations (proof build failed): " + ", ".join(sorted(blocked)))
    migration_units = {
        str(row["unit"])
        for theme, rows in preflight.items() if theme not in blocked
        for row in rows if row["migration_required"]
    }
    # Only pre-flight-approved protected selector migrations may be applied.
    structural_units = sorted(migration_units)
    bumped = apply_units(
        upstream_ref,
        upstream_units,
        conflict_set,
        protected_units,
        structural_units,
        blocked,
        deferred,
    )
    _write_step_summary(protected_units, bumped)
    if args.push:
        git("push", "origin", "HEAD:main")
        update_sync_branch(upstream_ref, push=True)
    else:
        print("Applied and validated locally; no push requested")


if __name__ == "__main__":
    main()
