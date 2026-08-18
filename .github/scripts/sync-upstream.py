import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_REMOTE = "upstream"
UPSTREAM_URL = "https://github.com/keiyoushi/extensions-source.git"
UPSTREAM_BRANCH = "main"
SYNC_BRANCH = "sync"


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


def apply_units(upstream_ref: str, units: list[str], conflict_units: set[str], protected_units: list[str]) -> list[str]:
    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    # 1. Apply upstream units (except conflict units where Nox wins)
    for unit in units:
        if unit in conflict_units:
            continue

        print(f"Applying {unit}")
        git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)

        if path_exists(upstream_ref, unit):
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

    # 2. Version Guard on all protected Nox units
    bumped = []
    for unit in protected_units:
        res = bump_version_code_if_needed(unit, upstream_ref)
        if res is not None:
            loc_raw, _, _, _, _, _, new_raw = res
            git("add", "--", f"{unit}/build.gradle.kts")
            bumped.append(f"{unit} -> versionCode={new_raw}")
            print(f"Version guard: bumped {unit} (versionCode {loc_raw} -> {new_raw})")
        else:
            print(f"Version guard: {unit} already ahead")

    git("diff", "--check")

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
    args = parser.parse_args()

    if args.dry_run and args.push:
        print("Use either --dry-run or --push")
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

    print_plan(
        base,
        upstream_ref,
        upstream_only_units,
        preserved_paths,
        main_only_units,
        conflict_units,
        protected_units,
    )

    update_sync_branch(upstream_ref, push=args.push)

    if not upstream_units:
        print("No upstream changes to apply")
        return

    if args.dry_run or not args.push:
        print("Dry run only; no changes were applied")
        return

    bumped = apply_units(upstream_ref, upstream_units, conflict_set, protected_units)
    _write_step_summary(protected_units, bumped)
    git("push", "origin", "HEAD:main")


if __name__ == "__main__":
    main()
