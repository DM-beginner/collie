"""Build and run an opt-in instrumentation smoke test on a paired emulator.

No real pane is targeted. The test APK is separate from the delivered app.
"""
import argparse
import os
import subprocess
from pathlib import Path
import tempfile
import zipfile
from build_apk import ROOT, run, signing_environment

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--connection-only", action="store_true", help="Test Tailscale lifecycle without a paired server or real VPN")
args = parser.parse_args()
test_class = "TailscaleSmoke" if args.connection_only else "DeviceSmoke"
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
run([adb, "-s", args.serial, "install", "-r", apk])
run([adb, "-s", args.serial, "shell", "am", "force-stop", "dev.dmbeginner.colliepocket"])
output = subprocess.check_output([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w",
                                  "dev.dmbeginner.colliepocket.tests/." + test_class], text=True, encoding="utf-8")
print(output)
if "result=PASS:" not in output or "INSTRUMENTATION_CODE: -1" not in output:
    raise SystemExit("Emulator smoke test failed.")
