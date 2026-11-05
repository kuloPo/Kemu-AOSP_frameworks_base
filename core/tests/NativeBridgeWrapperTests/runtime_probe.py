#!/usr/bin/env python3
"""Check non-zygote Native Bridge startup and failure behavior on a test device."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device-dir", default="/data/local/tmp/wrapped-native-bridge-runtime")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    prefix = [args.adb, "-s", args.serial]
    directory = args.device_dir

    def shell(parts):
        return subprocess.run(prefix + ["shell", shlex.join(parts)], capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=30)

    assert shell(["mkdir", directory]).returncode == 0, "Test directory already exists"
    subprocess.run(prefix + ["push", str(args.jar), directory + "/runtime-probe.jar"], check=True)
    assert shell(["chmod", "444", directory + "/runtime-probe.jar"]).returncode == 0
    library = "-XX:NativeBridge=libberberis_arm64.so"
    isa = "-XX:NativeBridgeInstructionSet=arm64"
    data = "-XX:NativeBridgeAppDataDir=" + directory
    valid = [library, isa, data]
    cases = [
        ("no-bridge", [], True),
        ("guest-startup", valid, True),
        ("missing-library", ["-XX:NativeBridge=libdoesnotexist.so", isa, data], False),
        ("disabled-library", ["-XX:NativeBridge=0", isa, data], False),
        ("empty-library", ["-XX:NativeBridge=", isa, data], False),
        ("missing-data", [library, isa], False),
        ("empty-data", [library, isa, "-XX:NativeBridgeAppDataDir="], False),
        ("relative-data", [library, isa, "-XX:NativeBridgeAppDataDir=."], False),
        ("root-data", [library, isa, "-XX:NativeBridgeAppDataDir=/"], False),
        ("trailing-slash", [library, isa, data + "/"], False),
        ("missing-isa", [library, data], False),
        ("empty-isa", [library, "-XX:NativeBridgeInstructionSet=", data], False),
        ("unknown-isa", [library, "-XX:NativeBridgeInstructionSet=not-an-isa", data], False),
        ("thumb2-isa", [library, "-XX:NativeBridgeInstructionSet=thumb2", data], False),
        ("host-isa", [library, "-XX:NativeBridgeInstructionSet=x86_64", data], False),
        ("wrong-bitness", [library, "-XX:NativeBridgeInstructionSet=arm", data], False),
        ("unsupported-guest", [library, "-XX:NativeBridgeInstructionSet=riscv64", data], False),
        ("zygote-conflict", valid + ["-Xzygote"], False),
        ("testing-conflict", valid + ["-Xforce-nb-testing"], False),
    ]
    # Cache failures must fall back to uncached bridge initialization.
    bad = directory + "/invalid"
    assert shell(["mkdir", bad]).returncode == 0
    assert shell(["touch", bad + "/code_cache"]).returncode == 0
    cases.append(("cache-is-file", [library, isa, "-XX:NativeBridgeAppDataDir=" + bad], True))
    cases.append(("cache-cannot-create", [
        library, isa, "-XX:NativeBridgeAppDataDir=" + directory + "/missing/app"], True))
    cases.append(("cache-permission-denied", [
        library, isa,
        "-XX:NativeBridgeAppDataDir=/data/user/0/com.android.nativebridge.probe.arm64plain"], True))
    results = []
    for name, options, expected in cases:
        command = ["env", "CLASSPATH=" + directory + "/runtime-probe.jar",
                   "/system/bin/app_process64", *options, "/system/bin", "RuntimeProbe"]
        if name == "cache-permission-denied":
            command = ["run-as", "com.android.nativebridge.probe.arm64wrapped", *command]
        result = shell(["sh", "-c", "echo RUNTIME_PROBE_PID=$$\nexec " + shlex.join(command)])
        pid = next(line.split("=", 1)[1] for line in result.stdout.splitlines()
                   if line.startswith("RUNTIME_PROBE_PID="))
        logs = subprocess.run(prefix + ["logcat", "-d", "--pid=" + pid],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", check=True).stdout
        (args.output / (name + "-logcat.txt")).write_text(logs, encoding="utf-8")
        passed_main = "RUNTIME_PROBE_PASS" in result.stdout
        # AndroidRuntime::start returns void and app_process can return zero after
        # JNI_CreateJavaVM fails. Require this process's rejection log and no Java
        # entry point, rather than treating the inherited exit status as success.
        rejected = any(reason in logs for reason in (
            "Native bridge app requires",
            "Native bridge app options cannot be combined",
            "Could not load native bridge for app",
            "Could not initialize native bridge for app"))
        expected_arch = "x86_64" if name == "no-bridge" else "aarch64"
        good = (result.returncode == 0
                and "RUNTIME_PROBE_PASS arch=" + expected_arch in result.stdout
                and not rejected) if expected else (rejected and not passed_main)
        cache_warnings = {
            "cache-is-file": "Code cache is not a directory",
            "cache-cannot-create": "Cannot create code cache directory",
            "cache-permission-denied": "Cannot stat code cache directory",
        }
        if name in cache_warnings:
            good = good and cache_warnings[name] in logs and not rejected
        if name == "cache-permission-denied":
            good = good and "Permission denied" in logs
        results.append({"case": name, "passed": good, "returncode": result.returncode,
                        "pid": pid, "rejection_logged": rejected,
                        "stdout": result.stdout, "stderr": result.stderr, "options": options})
        print(json.dumps(results[-1]), flush=True)
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n",
                                            encoding="utf-8")
    assert all(row["passed"] for row in results), "Runtime startup checks failed"


if __name__ == "__main__":
    main()
