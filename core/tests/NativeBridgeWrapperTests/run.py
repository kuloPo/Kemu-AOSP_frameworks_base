#!/usr/bin/env python3
"""Exercise the three JNI APKs on an already booted development device."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apks", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-fixed", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*parts, check=True):
        result = subprocess.run([args.adb, "-s", args.serial, *map(str, parts)],
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        if check and result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout + result.stderr

    def shell(*parts, check=True):
        return adb("shell", shlex.join(map(str, parts)), check=check)

    original = shell("getprop", "berberis.tracing").strip()
    results = []
    try:
        for name in ("arm64wrapped", "arm64plain", "x86wrapped"):
            package = "com.android.nativebridge.probe." + name
            adb("install", "-r", args.apks / (name + ".apk"))
            shell("am", "force-stop", package)
            shell("run-as", package, "rm", "-f", "files/result", "files/isolated-result", "nb-trace.txt")
            shell("setprop", "berberis.tracing", package + "=nb-trace.txt")
            before = shell("date", "+%s.%N").strip()
            launch = shell("am", "start", "-W", "-n",
                           package + "/com.android.nativebridge.probe.Probe",
                           "--ez", "isolated", "true", check=False)
            result = ""
            for _ in range(30):
                result = shell("run-as", package, "cat", "files/result", check=False).strip()
                if result.startswith("PASS"):
                    break
                time.sleep(0.2)
            isolated = ""
            for _ in range(100):
                isolated = shell("run-as", package, "cat", "files/isolated-result",
                                 check=False).strip()
                if isolated.startswith("PASS"):
                    break
                time.sleep(0.2)
            fields = dict(part.split("=", 1) for part in isolated.split() if "=" in part)
            isolated_pid = fields.get("pid", "")
            isolated_uid = int(fields.get("uid", "-1"))
            isolated_maps = (shell("cat", "/proc/" + isolated_pid + "/maps", check=False)
                             if isolated_pid.isdigit() else "")
            (args.output / (name + "-isolated-maps.txt")).write_text(
                isolated_maps, encoding="utf-8")
            pid = shell("pidof", package, check=False).strip()
            logs = adb("logcat", "-d", "-v", "threadtime", "-t", "3000")
            (args.output / (name + "-logcat.txt")).write_text(logs, encoding="utf-8")
            (args.output / (name + "-launch.txt")).write_text(launch, encoding="utf-8")
            trace = shell("cat", "/data/user/0/" + package + "/nb-trace.txt", check=False)
            (args.output / (name + "-trace.txt")).write_text(trace, encoding="utf-8")
            active_trace = "\n".join(line for line in trace.splitlines()
                                     if line.split() and line.split()[0] == pid)
            maps = shell("cat", "/proc/" + pid + "/maps", check=False) if pid.isdigit() else ""
            (args.output / (name + "-maps.txt")).write_text(maps, encoding="utf-8")
            checks = {
                "jni": result.startswith("PASS") and "answer=42" in result,
                "wrapper": ("wrapped=1" in result) == (name != "arm64plain"),
                "trace": ("native_bridge_initialize" in active_trace
                          and "app_isa='arm64'" in active_trace
                          and "/data/user/0/" + package + "/code_cache" in active_trace),
                "bridge_mapped": "libberberis_arm64.so" in maps,
                "isolated_jni": isolated.startswith("PASS") and fields.get("answer") == "42",
                "isolated_wrapper": fields.get("wrapped") == (
                    "null" if name == "arm64plain" else "1"),
                "isolated_uid": 90000 <= isolated_uid % 100000 <= 99999 and isolated_uid >= 0,
                "isolated_no_data_access": fields.get("data_access") == "false"
                                          and fields.get("cache_access") == "false",
                "isolated_bridge": ("libberberis_arm64.so" in isolated_maps)
                                   == name.startswith("arm64"),
            }
            results.append({"case": name, "started": before, "pid": pid,
                            "result": result, "isolated_result": isolated,
                            "checks": checks})
            print(json.dumps(results[-1]), flush=True)
            shell("am", "force-stop", package)
            if args.require_fixed:
                assert checks["jni"] and checks["wrapper"], results[-1]
                assert all(value for key, value in checks.items()
                           if key.startswith("isolated_")), results[-1]
                if name.startswith("arm64"):
                    assert checks["trace"] and checks["bridge_mapped"], results[-1]
                else:
                    assert not checks["bridge_mapped"], results[-1]

        # A mismatching package filter must not produce a trace, including in wrapped apps.
        if args.require_fixed:
            package = "com.android.nativebridge.probe.arm64wrapped"
            shell("run-as", package, "rm", "-f", "nb-filtered.txt")
            shell("setprop", "berberis.tracing", "com.android.other=nb-filtered.txt")
            shell("am", "start", "-W", "-n",
                  package + "/com.android.nativebridge.probe.Probe")
            trace = shell("run-as", package, "ls", "nb-filtered.txt", check=False)
            assert "No such file" in trace, trace
            shell("am", "force-stop", package)
            results.append({"case": "mismatching-package-filter", "passed": True})
    finally:
        shell("setprop", "berberis.tracing", original)
        (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
