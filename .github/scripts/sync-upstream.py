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


def _read_version_code_from_text(text: str) -> int | None:
    m = _VERSION_CODE_RE.search(text)
    return int(m.group(2)) if m else None


def read_version_code(ref: str, gradle_path: str) -> int | None:
    """Read versionCode from build.gradle.kts at the given git ref."""
    result = subprocess.run(
        ["git", "show", f"{ref}:{gradle_path}"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    return _read_version_code_from_text(result.stdout)


def bump_version_code_if_needed(unit: str, upstream_ref: str) -> int | None:
    """If upstream changed a protected unit, ensure local versionCode > upstream versionCode.

    Returns the new versionCode if a bump was performed, None otherwise.
    Only rewrites the file on disk; caller must `git add` it.
    """
    gradle_path = f"{unit}/build.gradle.kts"
    local_file = Path(gradle_path)
    if not local_file.exists():
        return None

    local_text = local_file.read_text()
    local_version = _read_version_code_from_text(local_text)
    upstream_version = read_version_code(upstream_ref, gradle_path)

    if local_version is None or upstream_version is None:
        return None

    # Bump only if upstream has caught up or surpassed Nox.
    if upstream_version < local_version:
        return None

    new_version = upstream_version + 1
    new_text = _VERSION_CODE_RE.sub(lambda m: f"{m.group(1)}{new_version}", local_text)
    local_file.write_text(new_text)
    return new_version


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


def _handle_conflict_unit(unit: str, upstream_ref: str, bumped: list[str]) -> None:
    new_ver = bump_version_code_if_needed(unit, upstream_ref)
    if new_ver is not None:
        git("add", "--", f"{unit}/build.gradle.kts")
        bumped.append(f"{unit} -> versionCode={new_ver}")
        print(f"Conflict resolved for Nox (bumped versionCode to {new_ver}): {unit}")
    else:
        print(f"Conflict resolved for Nox (versionCode already ahead): {unit}")


def apply_units(upstream_ref: str, units: list[str], conflict_units: set[str]) -> list[str]:
    git("merge", "--no-ff", "--no-commit", "-s", "ours", upstream_ref)

    bumped = []
    for unit in units:
        if unit in conflict_units:
            _handle_conflict_unit(unit, upstream_ref, bumped)
            continue

        print(f"Applying {unit}")
        git("rm", "-r", "--ignore-unmatch", "--quiet", "--", unit)

        if path_exists(upstream_ref, unit):
            git("restore", f"--source={upstream_ref}", "--staged", "--worktree", "--", unit)

    git("diff", "--check")

    commit_msg = "Sync upstream"
    if bumped:
        commit_msg += "\n\nNox-resolved conflicts touched by upstream:\n" + "\n".join(f"  - {b}" for b in bumped)

    git("commit", "-m", commit_msg)
    return bumped


def _write_step_summary(conflict_units: list[str], bumped: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path or not conflict_units:
        return
    lines = ["\n## Nox-Resolved Conflicts (Modified by both upstream and Nox)\n\n"]
    for unit in conflict_units:
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

    print_plan(base, upstream_ref, upstream_only_units, preserved_paths, main_only_units, conflict_units)

    update_sync_branch(upstream_ref, push=args.push)

    if not upstream_units:
        print("No upstream changes to apply")
        return

    if args.dry_run or not args.push:
        print("Dry run only; no changes were applied")
        return

    bumped = apply_units(upstream_ref, upstream_units, conflict_set)
    _write_step_summary(conflict_units, bumped)
    git("push", "origin", "HEAD:main")


if __name__ == "__main__":
    main()
