#!/usr/bin/env python3
"""Build wrapped ARM64, plain ARM64, and wrapped x86_64 JNI probes on Linux.

Use a built AOSP checkout and an Android NDK sysroot. The compiler and packaging
tools come from AOSP; no Gradle downloads or host-specific paths are required.
"""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aosp-root", type=Path, required=True)
    parser.add_argument("--clang", type=Path, required=True)
    parser.add_argument("--ndk-sysroot", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.aosp_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    src = Path(__file__).resolve().parent
    host = root / "out/host/linux-x86"
    jdk = root / "prebuilts/jdk/jdk21/linux-x86/bin"
    clang = args.clang.resolve()
    android = root / "prebuilts/sdk/current/public/android.jar"
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk.parent)
    env["PATH"] = str(jdk) + os.pathsep + env["PATH"]

    def run(*cmd):
        subprocess.run([str(x) for x in cmd], check=True, env=env)

    classes = output / "classes"
    classes.mkdir()
    run(jdk / "javac", "-source", "8", "-target", "8", "-classpath", android,
        "-d", classes, src / "Probe.java", src / "IsolatedProbe.java",
        src / "RuntimeProbe.java")
    dex = output / "dex"
    dex.mkdir()
    run(host / "bin/d8", "--min-api", "26", "--lib", android,
        "--output", dex, *classes.rglob("*.class"))
    with zipfile.ZipFile(output / "runtime-probe.jar", "w") as jar:
        jar.write(dex / "classes.dex", "classes.dex")
    for name, abi, triple, wrapped in [
            ("arm64wrapped", "arm64-v8a", "aarch64-linux-android", True),
            ("arm64plain", "arm64-v8a", "aarch64-linux-android", False),
            ("x86wrapped", "x86_64", "x86_64-linux-android", True)]:
        work = output / name
        work.mkdir()
        package = "com.android.nativebridge.probe." + name
        manifest = work / "AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="' + package + '">'
            '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>'
            '<application android:debuggable="true" android:extractNativeLibs="true">'
            '<activity android:name="com.android.nativebridge.probe.Probe" '
            'android:exported="true"/>'
            '<service android:name="com.android.nativebridge.probe.IsolatedProbe" '
            'android:isolatedProcess="true" android:exported="false"/>'
            '</application></manifest>')
        unsigned = work / "unsigned.apk"
        run(host / "bin/aapt2", "link", "-o", unsigned, "-I", android, "--manifest", manifest)
        library = work / "libnbprobe.so"
        run(clang, "--target=" + triple + "26", "--sysroot=" + str(args.ndk_sysroot),
            "-fuse-ld=lld", "-shared", "-fPIC", "-nostdlib",
            "-I" + str(args.ndk_sysroot / "usr/include"),
            "-I" + str(args.ndk_sysroot / "usr/include" / triple),
            "-Wl,-soname,libnbprobe.so", "-o", library, src / "probe.c")
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as apk:
            apk.write(dex / "classes.dex", "classes.dex")
            apk.write(library, "lib/" + abi + "/libnbprobe.so")
            if wrapped:
                info = zipfile.ZipInfo("lib/" + abi + "/wrap.sh")
                info.external_attr = 0o100755 << 16
                apk.writestr(info, (src / "wrap.sh").read_bytes())
        aligned = work / "aligned.apk"
        run(host / "bin/zipalign", "-f", "4", unsigned, aligned)
        run(jdk / "java", "-Djava.library.path=" + str(host / "lib64"),
            "-jar", host / "framework/signapk.jar",
            root / "build/target/product/security/testkey.x509.pem",
            root / "build/target/product/security/testkey.pk8",
            aligned, output / (name + ".apk"))
        print(package, "->", output / (name + ".apk"), flush=True)


if __name__ == "__main__":
    main()
