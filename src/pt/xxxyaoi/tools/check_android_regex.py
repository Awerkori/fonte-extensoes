#!/usr/bin/env python3
"""Compile the actual CSS-reader patterns on Android ICU, not the host JDK.

Requires adb, a connected Android device, a JDK and the project's Android SDK.
Only a temporary DEX is installed under /data/local/tmp; app data is untouched.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    repo = module.parents[2]
    source = (module / "src/eu/kanade/tachiyomi/extension/pt/xxxyaoi/CssReader.kt").read_text()
    sdk = None
    if (repo / "local.properties").exists():
        match = re.search(r"^sdk.dir=(.+)$", (repo / "local.properties").read_text(), re.M)
        sdk = match[1] if match else None
    if not sdk:
        sdk = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
    d8 = sorted((Path(sdk) / "build-tools").glob("*/d8"))[-1]
    gap = re.search(r'val gap = """(.*?)"""', source, re.S)[1]
    patterns = []
    for name, pattern in re.findall(r'val (\w+) = Regex\("""(.*?)"""', source, re.S):
        pattern = pattern.replace("$gap", gap).replace("${Regex.escape(property)}", r"\Q--reader-test\E")
        patterns.append((name, pattern))
    assert len(patterns) >= 8, "Pattern extraction incomplete"
    initializers = ",\n".join("{" + json.dumps(name) + "," + json.dumps(pattern) + "}" for name, pattern in patterns)
    java = '''import java.util.regex.Pattern;
public class ReaderRegexCheck {
  public static void main(String[] args) {
    String[][] patterns = { INITIALIZERS };
    for (String[] entry : patterns) {
      Pattern p = Pattern.compile(entry[1]);
      if (entry[0].equals("rootRule") && !p.matcher(":root { --reader-test: abc; }").find()) {
        throw new AssertionError("Root rule does not match");
      }
      System.out.println("PASS " + entry[0]);
    }
    System.out.println("ANDROID_ICU_PASS=" + patterns.length);
  }
}'''.replace("INITIALIZERS", initializers)
    adb = ["adb", "-s", args.serial]
    with tempfile.TemporaryDirectory(prefix="xxxyaoi-regex-") as tmp:
        tmp = Path(tmp)
        (tmp / "ReaderRegexCheck.java").write_text(java)
        subprocess.run(["javac", "--release", "8", str(tmp / "ReaderRegexCheck.java")], check=True)
        subprocess.run([str(d8), "--min-api", "26", "--output", str(tmp), str(tmp / "ReaderRegexCheck.class")], check=True)
        remote = subprocess.check_output(adb + ["shell", "mktemp", "-d", "/data/local/tmp/xxxyaoi-regex.XXXXXX"], text=True).strip()
        if not re.fullmatch(r"/data/local/tmp/xxxyaoi-regex\.[A-Za-z0-9]+", remote):
            raise RuntimeError("Unexpected temporary path")
        try:
            subprocess.run(adb + ["push", str(tmp / "classes.dex"), remote + "/classes.dex"], check=True)
            subprocess.run(adb + ["shell", "env", "CLASSPATH=" + remote + "/classes.dex", "app_process", "/system/bin", "ReaderRegexCheck"], check=True)
        finally:
            subprocess.run(adb + ["shell", "rm", remote + "/classes.dex"], check=True)
            subprocess.run(adb + ["shell", "rmdir", remote], check=True)


if __name__ == "__main__":
    main()
