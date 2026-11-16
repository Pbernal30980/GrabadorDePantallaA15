# Uso: python3 tap.py "regex"  → toca el primer elemento cuyo texto o descripción coincida
import re, subprocess, sys, time
pat = re.compile(sys.argv[1], re.I)
for intento in range(6):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
        label = (t.group(1) if t else "") + " " + (d.group(1) if d else "")
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b and pat.search(label.strip()):
            x1, y1, x2, y2 = map(int, b.groups())
            print("TOCANDO:", label.strip(), (x1 + x2) // 2, (y1 + y2) // 2)
            subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
            sys.exit(0)
    time.sleep(1.5)
print("NO ENCONTRADO:", sys.argv[1])
open("/tmp/out/ui_" + re.sub(r"\W+", "_", sys.argv[1])[:20] + ".xml", "w").write(xml)
sys.exit(0)
