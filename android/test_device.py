"""Build and run an opt-in instrumentation smoke test on a paired emulator.

No real pane is targeted. The test APK is separate from the delivered app.
"""
import argparse
import os
import subprocess
import time
import re
from pathlib import Path
import tempfile
import zipfile
from build_apk import ROOT, run, signing_environment

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5554")
group = parser.add_mutually_exclusive_group()
group.add_argument("--connection-only", action="store_true", help="Test Tailscale lifecycle without a paired server or real VPN")
group.add_argument("--wake-only", action="store_true", help="Emulator-only external wake-up fixture; requires Tailscale NOT installed")
args = parser.parse_args()
test_class = "TailscaleWakeSmoke" if args.wake_only else "TailscaleSmoke" if args.connection_only else "DeviceSmoke"
if not args.serial.startswith("emulator-"):
    parser.error("This smoke test intentionally accepts emulator serials only.")
sdk = Path(os.environ["ANDROID_HOME"])
java = Path(os.environ["JAVA_HOME"])
tools = sdk / "build-tools/35.0.0"
platform = sdk / "platforms/android-35/android.jar"
work = Path(tempfile.mkdtemp(prefix="smoke-", dir=ROOT / "build"))
classes, dex = work / "classes", work / "dex"
classes.mkdir(); dex.mkdir()
manifest = work / "AndroidManifest.xml"
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.dmbeginner.colliepocket.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Collie Pocket test"/><instrumentation android:name=".{test_class}" android:targetPackage="dev.dmbeginner.colliepocket"/></manifest>''', encoding="utf-8")
app_classes = max((ROOT / "build").glob("apk-*/classes.jar"), key=lambda path: path.stat().st_mtime)
run([java / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8", "-cp", str(platform) + os.pathsep + str(app_classes),
     "-d", classes, ROOT / "tests" / (test_class + ".java")])
jar = work / "classes.jar"
with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in classes.rglob("*.class"):
        archive.write(path, path.relative_to(classes).as_posix())
run([tools / "d8.bat", "--lib", platform, "--classpath", app_classes, "--min-api", "26", "--output", dex, jar])
unsigned = work / "test-unsigned.apk"
run([tools / "aapt2.exe", "link", "-I", platform, "--manifest", manifest, "-o", unsigned])
with zipfile.ZipFile(unsigned, "a") as archive:
    archive.write(dex / "classes.dex", "classes.dex")
aligned = work / "test-aligned.apk"
run([tools / "zipalign.exe", "-f", "-p", "4", unsigned, aligned])
key, env = signing_environment(java)
apk = work / "test.apk"
run([tools / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "collie-pocket",
     "--ks-pass", "env:COLLIE_SIGNING_PASSWORD", "--out", apk, aligned], env)
adb = sdk / "platform-tools/adb.exe"
fixture_installed = False
standalone_prepared = False
def wake_mode(mode):
    output = subprocess.check_output([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", "-e", "mode", mode,
                                      "dev.dmbeginner.colliepocket.tests/.TailscaleWakeSmoke"], text=True, encoding="utf-8")
    if "result=PASS:" not in output or "INSTRUMENTATION_CODE: -1" not in output:
        raise RuntimeError("Wake test " + mode + " failed: " + output)

def wake_state():
    return subprocess.check_output([str(adb), "-s", args.serial, "shell", "content", "call", "--uri",
        "content://dev.dmbeginner.collie.tests.tailscale-state", "--method", "state"], text=True)

def await_wake_return(launches):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        state = wake_state()
        activities = subprocess.check_output([str(adb), "-s", args.serial, "shell", "dumpsys", "activity", "activities"], text=True)
        if (f"launches={launches}" in state and "after_wake=true" in state and "visible=false" in state
                and re.search(r"(?:mResumedActivity:|topResumedActivity=).*dev\.dmbeginner\.colliepocket/\.MainActivity", activities)):
            return
        time.sleep(0.25)
    raise RuntimeError("Ordinary app launch did not wake and return as expected: " + state)

try:
    if args.wake_only:
        present = subprocess.check_output([str(adb), "-s", args.serial, "shell", "pm", "list", "packages", "com.tailscale.ipn"], text=True)
        if "package:com.tailscale.ipn" in present:
            raise SystemExit("Refusing fixture install: emulator already contains Tailscale. Use a separate test AVD.")
        fixture_classes, fixture_dex = work / "fixture-classes", work / "fixture-dex"
        fixture_classes.mkdir(); fixture_dex.mkdir()
        run([java / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8", "-cp", platform, "-d", fixture_classes,
             ROOT / "tests/ColdStartFixture.java", ROOT / "tests/fixture/IPNReceiver.java"])
        fixture_jar = work / "fixture.jar"
        with zipfile.ZipFile(fixture_jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in fixture_classes.rglob("*.class"): archive.write(path, path.relative_to(fixture_classes).as_posix())
        run([tools / "d8.bat", "--lib", platform, "--min-api", "26", "--output", fixture_dex, fixture_jar])
        fixture_manifest = work / "fixture-manifest.xml"
        fixture_manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tailscale.ipn"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Tailscale Cold Start Test" android:theme="@android:style/Theme.Material.NoActionBar"><activity android:name=".ColdStartFixture" android:launchMode="singleTask" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity><receiver android:name=".IPNReceiver" android:exported="true"/><provider android:name=".ColdStartFixture$Probe" android:authorities="dev.dmbeginner.collie.tests.tailscale-state" android:exported="true"/></application></manifest>""", encoding="utf-8")
        fixture_unsigned, fixture_aligned, fixture_apk = work / "fixture-unsigned.apk", work / "fixture-aligned.apk", work / "fixture.apk"
        run([tools / "aapt2.exe", "link", "-I", platform, "--manifest", fixture_manifest, "-o", fixture_unsigned])
        with zipfile.ZipFile(fixture_unsigned, "a") as archive: archive.write(fixture_dex / "classes.dex", "classes.dex")
        run([tools / "zipalign.exe", "-f", "-p", "4", fixture_unsigned, fixture_aligned])
        run([tools / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "collie-pocket", "--ks-pass", "env:COLLIE_SIGNING_PASSWORD", "--out", fixture_apk, fixture_aligned], env)
        run([adb, "-s", args.serial, "install", fixture_apk])
        fixture_installed = True
    run([adb, "-s", args.serial, "install", "-r", apk])
    run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
    if args.wake_only:
        # Instrumentation can grant background-launch privileges: test the signed app without it.
        standalone_prepared = True
        wake_mode("prepare")
        run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", "dev.dmbeginner.colliepocket/.MainActivity"])
        await_wake_return(1)
        # A recreated automatic return must preserve the cooldown even without saved state.
        run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
        run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", "dev.dmbeginner.colliepocket/.MainActivity",
             "--ez", "dev.dmbeginner.colliepocket.TAILSCALE_RETURN", "true"])
        time.sleep(9)
        if "launches=1" not in wake_state(): raise RuntimeError("Recreated automatic return discarded its cooldown")
        # A genuinely fresh task within the same minute must be able to wake again.
        run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
        run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", "dev.dmbeginner.colliepocket/.MainActivity"])
        await_wake_return(2)
        time.sleep(7)
        if "launches=2" not in wake_state(): raise RuntimeError("Automatic return caused repeated Tailscale jumps")
        print("PASS: recent prior wake does not block a fresh task; recreated automatic return preserves cooldown; immediate fresh task wakes again; signed app returns without instrumentation or injected Back.")
        run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
        wake_mode("restore")
        standalone_prepared = False
        run([adb, "-s", args.serial, "shell", "pm", "clear", "com.tailscale.ipn"])
    output = subprocess.check_output([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w",
                                      "dev.dmbeginner.colliepocket.tests/." + test_class], text=True, encoding="utf-8")
    print(output)
    if "result=PASS:" not in output or "INSTRUMENTATION_CODE: -1" not in output:
        raise SystemExit("Emulator smoke test failed.")
finally:
    try:
        if standalone_prepared:
            run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
            wake_mode("restore")
    finally:
        if fixture_installed: run([adb, "-s", args.serial, "uninstall", "com.tailscale.ipn"])
