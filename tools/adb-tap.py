"""Tap the first on-screen node whose text or content-desc contains the given string (uiautomator dump).
usage: python tools/adb-tap.py "Spotify olmadan" [--long]"""
import re, subprocess, sys
ADB = r"C:\Users\shades\AppData\Local\Android\Sdk\platform-tools\adb.exe"
needle = sys.argv[1]
subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
xml = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "replace")
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    text = (re.search(r' text="([^"]*)"', node) or [None, ""])[1]
    desc = (re.search(r'content-desc="([^"]*)"', node) or [None, ""])[1]
    if needle.lower() in text.lower() or needle.lower() in desc.lower():
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        if "--long" in sys.argv:
            subprocess.run([ADB, "shell", "input", "swipe", str(x), str(y), str(x), str(y), "700"])
        else:
            subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
        print(f"tapped '{text or desc}' at {x},{y}")
        sys.exit(0)
print("not found:", needle); sys.exit(1)
