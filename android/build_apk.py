"""Build a signed, dependency-free Android APK with Google's SDK tools and JDK.

The signing key stays outside the repository. Its random password is protected by
Windows DPAPI for the current Windows user. Never send the key to another person.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = "0.1.1"
VERSION_CODE = 2


def run(args, env=None):
    subprocess.run([str(a) for a in args], check=True, env=env)


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def protect(data, decrypt=False):
    source_buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    source = Blob(len(data), source_buffer)
    target = Blob()
    operation = (ctypes.windll.crypt32.CryptUnprotectData if decrypt
                 else ctypes.windll.crypt32.CryptProtectData)
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                          ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(target.data)


def signing_environment(java):
    folder = Path(os.environ["LOCALAPPDATA"]) / "collie-pocket" / "signing"
    folder.mkdir(parents=True, exist_ok=True)
    # Restrict inheritance before storing the password or key. Pass paths through
    # the environment, never interpolate them into PowerShell code.
    script = """
    $path = $env:COLLIE_SIGNING_DIRECTORY
    $acl = [System.Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($owner)
    foreach ($sid in @($owner.Value,'S-1-5-18','S-1-5-32-544')) {
        $identity = [System.Security.Principal.SecurityIdentifier]::new($sid)
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    [System.IO.DirectoryInfo]::new($path).SetAccessControl($acl)
    """
    env = os.environ.copy()
    env["COLLIE_SIGNING_DIRECTORY"] = str(folder)
    run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], env)
    password_file = folder / "password.dpapi"
    key = folder / "collie-pocket.p12"
    if not password_file.exists():
        if key.exists():
            raise RuntimeError("Signing password is missing; preserve your existing key and restore its backup.")
        password_file.write_bytes(protect(secrets.token_urlsafe(32).encode("ascii")))
    password = protect(password_file.read_bytes(), decrypt=True).decode("ascii")
    env["COLLIE_SIGNING_PASSWORD"] = password
    if not key.exists():
        run([java / "bin/keytool.exe", "-genkeypair", "-keystore", key,
             "-storetype", "PKCS12", "-alias", "collie-pocket", "-keyalg", "RSA",
             "-keysize", "3072", "-validity", "10000", "-dname", "CN=Collie Pocket",
             "-storepass:env", "COLLIE_SIGNING_PASSWORD",
             "-keypass:env", "COLLIE_SIGNING_PASSWORD"], env)
    return key, env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--java", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--default-server", default="", help="Personal build only; never committed to source")
    options = parser.parse_args()
    if not options.sdk or not options.java:
        parser.error("Set ANDROID_HOME and JAVA_HOME, or use Build-Android.ps1 on the configured computer.")
    sdk, java = options.sdk.resolve(), options.java.resolve()
    tools = sdk / "build-tools/35.0.0"
    platform = sdk / "platforms/android-35/android.jar"
    for required in [platform, tools / "aapt2.exe", java / "bin/javac.exe"]:
        if not required.is_file():
            parser.error("Required build tool not found: " + str(required))
    os.environ["JAVA_HOME"] = str(java)
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    # Unique working directory: no recursive deletion, old builds remain reviewable.
    work = Path(tempfile.mkdtemp(prefix="apk-", dir=build))
    res = work / "res"
    shutil.copytree(ROOT / "app/src/main/res", res)
    generated, classes, dex = [work / name for name in ("generated", "classes", "dex")]
    for folder in (generated, classes, dex):
        folder.mkdir()
    if options.default_server:
        test_classes = work / "validator"
        test_classes.mkdir()
        source = ROOT / "app/src/main/java/dev/dmbeginner/colliepocket/ServerAddress.java"
        check = test_classes / "Check.java"
        check.write_text("class Check { public static void main(String[] a) { System.out.print(dev.dmbeginner.colliepocket.ServerAddress.normalize(a[0])); } }", encoding="utf-8")
        run([java / "bin/javac.exe", "-encoding", "UTF-8", "-d", test_classes, source, check])
        normalized = subprocess.check_output([str(java / "bin/java.exe"), "-cp", str(test_classes), "Check", options.default_server], text=True)
        strings = res / "values/strings.xml"
        tree = ET.parse(strings)
        tree.find(".//string[@name='default_server']").text = normalized
        tree.write(strings, encoding="utf-8", xml_declaration=True)
    compiled = work / "resources.zip"
    resources = work / "resources.apk"
    manifest = work / "AndroidManifest.xml"
    manifest_tree = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    manifest_tree.getroot().set("package", "dev.dmbeginner.colliepocket")
    manifest_tree.write(manifest, encoding="utf-8", xml_declaration=True)
    run([tools / "aapt2.exe", "compile", "--dir", res, "-o", compiled])
    run([tools / "aapt2.exe", "link", "-o", resources, "-I", platform,
         "-A", ROOT / "app/src/main/assets",
         "--manifest", manifest, "--java", generated,
         "--min-sdk-version", "26", "--target-sdk-version", "35", "--version-code", str(VERSION_CODE),
         "--version-name", VERSION, compiled])
    sources = list((ROOT / "app/src/main/java").rglob("*.java")) + list(generated.rglob("*.java"))
    args_file = work / "sources.txt"
    args_file.write_text("\n".join('"' + p.as_posix() + '"' for p in sources), encoding="utf-8")
    run([java / "bin/javac.exe", "--release", "8", "-encoding", "UTF-8",
         "-cp", platform, "-d", classes, "@" + str(args_file)])
    jar = work / "classes.jar"
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for entry in classes.rglob("*.class"):
            archive.write(entry, entry.relative_to(classes).as_posix())
    run([tools / "d8.bat", "--lib", platform, "--min-api", "26", "--output", dex, jar])
    with zipfile.ZipFile(resources, "a") as archive:
        for entry in dex.glob("*.dex"):
            archive.write(entry, entry.name, compress_type=zipfile.ZIP_STORED)
    aligned = work / "aligned.apk"
    run([tools / "zipalign.exe", "-f", "-p", "4", resources, aligned])
    key, signing_env = signing_environment(java)
    destination = build / ("collie-pocket-" + VERSION + ".apk")
    run([tools / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "collie-pocket",
         "--ks-pass", "env:COLLIE_SIGNING_PASSWORD", "--key-pass", "env:COLLIE_SIGNING_PASSWORD",
         "--out", destination, aligned], signing_env)
    run([tools / "apksigner.bat", "verify", "--verbose", destination])
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".apk.sha256").write_text(digest + "  " + destination.name + "\n", encoding="ascii")
    print("APK:", destination)
    print("SHA256:", digest)


if __name__ == "__main__":
    main()
