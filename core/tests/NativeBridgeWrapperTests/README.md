# Wrapped Native Bridge regression probes

Build on Linux with an AOSP checkout containing the Framework and ART patch pair.
Use the pinned AOSP Clang, its built aapt2/d8/zipalign/signapk tools, and an NDK
sysroot (only headers are needed for the dependency-free JNI probe):

    python3 build.py --aosp-root "$AOSP" --clang "$CLANG" \
      --ndk-sysroot "$NDK_SYSROOT" --output "$OUTPUT"

Install and run on a development device started with the project's launcher.
The device must allow adb root so write-only Berberis traces and process maps
can be read. The runner temporarily changes berberis.tracing and restores it.

    adb -s "$SERIAL" root
    python3 run.py --adb adb --serial "$SERIAL" --apks "$OUTPUT" \
      --output "$RESULTS" --require-fixed

The tests load a JNI library and assert its result for wrapped ARM64, ordinary
ARM64, and wrapped x86_64 apps. The ARM64 checks require an initialization trace
from the final app PID containing the guest ISA and its own code_cache path.
A matching package filter and relative trace filename exercise both package
name and private-directory metadata. A mismatching filter must create no trace.
The x86_64 wrapped process must not map Berberis.
Each app also binds an isolated service and receives its JNI result over Binder.
The checks require an isolated UID, no access to the app data or cache directory,
the expected wrapper environment, and Berberis mappings only for ARM64.

Run the startup and failure checks separately:

    python3 runtime_probe.py --adb adb --serial "$SERIAL" \
      --jar "$OUTPUT/runtime-probe.jar" --output "$RUNTIME_RESULTS"

These use a fresh /data/local/tmp/wrapped-native-bridge-runtime directory and
leave its test files for inspection. They check valid guest startup, no-bridge
startup, malformed/missing metadata, host ISA, mismatched bitness, unsupported
guest ISA, missing library, and conflicting modes. An invalid cache directory,
a missing parent, and access to another app's data directory while running as
the wrapped probe's UID must log a cache warning and still reach Java main
with the guest environment. The permission case requires
the probe APKs to be installed first. An alternate fresh device directory can be
selected with --device-dir.
Do not reuse that test directory without reviewing the previous artifacts.

The same runtime-probe.jar can verify the actual Framework shell-argument helper:

    adb -s "$SERIAL" shell 'CLASSPATH=/data/local/tmp/wrapped-native-bridge-runtime/runtime-probe.jar app_process64 /system/bin RuntimeProbe --wrapper-args'

The helper test checks quotes, spaces and shell metacharacters, host ISA and
disabled-bridge cases, and missing or malformed app data directories.
FrameworksCoreTests also contains com.android.internal.os.WrapperInitTest;
it invokes the package-private helper reflectively across class loaders:

    atest FrameworksCoreTests:com.android.internal.os.WrapperInitTest

ART's ParsedOptionsTest covers the argument contract and preservation of the
host boot-image instruction set.

For negative app_process cases, the runner requires both an explicit rejection
log from that PID and absence of the Java entry marker. app_process inherits
AndroidRuntime::start's void return and may exit with status zero after VM
creation failure; exit status alone is not a valid success criterion.
