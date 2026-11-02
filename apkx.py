"""Export installed Android apps to APK files over adb.

usage:
  python apkx.py list [filter]              list installed third-party packages
  python apkx.py pull <package> [-o DIR]    pull base.apk + split APKs into DIR/<package>-<version>/
  python apkx.py pull <package> --bundle    also pack them into one <package>-<version>.apks (zip)
  python apkx.py libs <package> [-o DIR]    extract native .so files (lib/<abi>/*.so) from the pulled APKs

Needs adb on PATH (or ADB env var) and a device with USB debugging allowed.
Only reads from the device; nothing is installed or changed on it.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


def adb_path():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found: put platform-tools on PATH or set ADB=/path/to/adb")
    return found


def adb(*args, serial=None):
    cmd = [adb_path()] + (["-s", serial] if serial else []) + list(args)
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{res.stderr.strip() or res.stdout.strip()}")
    return res.stdout


def require_device(serial):
    lines = [l.split() for l in adb("devices").splitlines()[1:] if l.strip()]
    ready = [l[0] for l in lines if len(l) > 1 and l[1] == "device"]
    if serial:
        if serial not in ready:
            sys.exit(f"device {serial} not ready; seen: {lines}")
        return serial
    if not ready:
        hint = " (tap 'Allow USB debugging' on the phone)" if any(l[1] == "unauthorized" for l in lines if len(l) > 1) else ""
        sys.exit("no device connected" + hint)
    if len(ready) > 1:
        sys.exit(f"several devices connected, pick one with -s: {ready}")
    return ready[0]


def package_version(pkg, serial):
    out = adb("shell", "dumpsys", "package", pkg, serial=serial)
    name = re.search(r"versionName=(\S+)", out)
    code = re.search(r"versionCode=(\d+)", out)
    return (name.group(1) if name else "unknown"), (code.group(1) if code else "0")


def apk_paths(pkg, serial):
    out = adb("shell", "pm", "path", pkg, serial=serial)
    paths = [l[len("package:"):].strip() for l in out.splitlines() if l.startswith("package:")]
    if not paths:
        sys.exit(f"package {pkg} not installed")
    return paths


def cmd_list(args):
    serial = require_device(args.serial)
    out = adb("shell", "pm", "list", "packages", "-3", serial=serial)
    pkgs = sorted(l[len("package:"):].strip() for l in out.splitlines() if l.startswith("package:"))
    for p in pkgs:
        if not args.filter or args.filter.lower() in p.lower():
            print(p)


def cmd_pull(args):
    serial = require_device(args.serial)
    name, code = package_version(args.package, serial)
    dest = Path(args.out) / f"{args.package}-{name}"
    dest.mkdir(parents=True, exist_ok=True)
    pulled = []
    for remote in apk_paths(args.package, serial):
        local = dest / Path(remote).name
        print(f"pull {remote}")
        adb("pull", remote, str(local), serial=serial)
        pulled.append(local)
    (dest / "version.txt").write_text(f"{args.package}\nversionName={name}\nversionCode={code}\n", encoding="utf-8")
    print(f"{len(pulled)} APK(s) -> {dest}")
    if args.bundle:
        bundle = Path(args.out) / f"{args.package}-{name}.apks"
        with zipfile.ZipFile(bundle, "w", zipfile.ZIP_STORED) as z:
            for f in pulled:
                z.write(f, f.name)
        print(f"bundle -> {bundle}")
    if len(pulled) > 1:
        print("note: split APKs; install them together (adb install-multiple ...) or use the .apks bundle")
    return dest


def cmd_libs(args):
    dirs = sorted(d for d in Path(args.out).glob(args.package + "-*") if d.is_dir())
    if not dirs:
        sys.exit(f"no pulled copy of {args.package} in {args.out}; run pull first")
    src = dirs[-1]
    libdir = src / "lib"
    count = 0
    for apk in sorted(src.glob("*.apk")):
        with zipfile.ZipFile(apk) as z:
            for info in z.infolist():
                if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                    target = libdir / Path(info.filename).relative_to("lib")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(z.read(info))
                    print(f"{apk.name}: {info.filename} ({info.file_size} bytes)")
                    count += 1
    print(f"{count} native lib(s) -> {libdir}")


def main():
    p = argparse.ArgumentParser(description="Export installed Android apps to APK files over adb.")
    p.add_argument("-s", "--serial", help="device serial when several are connected")
    sub = p.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("list"); l.add_argument("filter", nargs="?"); l.set_defaults(fn=cmd_list)
    for name, fn in (("pull", cmd_pull), ("libs", cmd_libs)):
        s = sub.add_parser(name); s.add_argument("package"); s.add_argument("-o", "--out", default="out")
        s.set_defaults(fn=fn)
        if name == "pull":
            s.add_argument("--bundle", action="store_true", help="also write a .apks zip of all splits")
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
