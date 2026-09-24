#!/usr/bin/env python3
"""验证「我的」页新增的三行：辅助记账、省电策略放行、重新连接通知监听。

独立于 ui-verify.ps1 的小检查：只负责进设置页、确认三行渲染出来了、截一张图。
"""
import os
import re
import subprocess
import sys
import time

ADB = r"D:\develop\android-sdk\platform-tools\adb.exe"
PKG = "com.local.autoledger.debug"
OUT = r"D:\Project\AutoLedger\dist\ui-verify"

WANT = [
    "辅助记账（读支付结果页）",
    "省电策略放行",
    "重新连接通知监听",
]


def adb(args, check=True, timeout=90):
    r = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    out = (r.stdout or b"").decode("utf-8", "ignore") + \
          (r.stderr or b"").decode("utf-8", "ignore")
    if check and r.returncode != 0:
        raise RuntimeError("adb %s -> %s" % (" ".join(args), out))
    return out.strip()


def dump():
    adb(["shell", "rm", "-f", "/sdcard/ui.xml"], check=False)
    out = adb(["shell", "uiautomator", "dump", "/sdcard/ui.xml"])
    if "dumped" not in out:
        raise RuntimeError("uiautomator dump 失败: " + out)
    # dump 命令返回后文件可能还没刷完盘，稍等并重试几次
    for _ in range(5):
        time.sleep(1.5)
        xml = adb(["shell", "cat", "/sdcard/ui.xml"], check=False)
        if "<node" in xml:
            return xml
    raise RuntimeError("dump 文件读不到: " + out)


def center(xml, text):
    """按 text 或 content-desc 找节点中心点"""
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        if 'text="%s"' % text in n or 'content-desc="%s"' % text in n:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def texts(xml, limit=40):
    """把界面上可见的文字列出来，dump 找不到东西时用来定位"""
    seen = []
    for m in re.finditer(r'text="([^"]+)"', xml):
        t = m.group(1)
        if t and t not in seen:
            seen.append(t)
    return seen[:limit]


def tap(x, y):
    adb(["shell", "input", "tap", str(x), str(y)])


def ensure_fold(xml):
    """三行现在收在「稳定运行」折叠区里：没展开就点一下表头再 dump。"""
    if all(t in xml for t in WANT):
        return xml
    pos = center(xml, "稳定运行")
    if pos is None:
        return xml
    tap(*pos)
    time.sleep(1.5)
    return dump()


def main():
    os.makedirs(OUT, exist_ok=True)
    # --no-launch：App 已经停在「我的」页（ui-verify 跑完就是），别再重启它
    no_launch = "--no-launch" in sys.argv

    if no_launch:
        xml = dump()
        with open(r"D:\Project\AutoLedger\.workbuddy\ui_settings.xml", "w", encoding="utf-8") as f:
            f.write(xml)
    else:
        xml = launch_and_dump()

    xml = ensure_fold(xml)

    ok = True
    for t in WANT:
        hit = t in xml
        print(("  [OK]   " if hit else "  [FAIL] ") + t)
        ok = ok and hit

    png = os.path.join(OUT, "30-设置页-辅助记账与省电放行.png")
    with open(png, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"],
                               capture_output=True, timeout=60).stdout)
    print("  截图 " + png)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


def launch_and_dump():
    print("安装调试包")
    print(adb(["install", "-r", r"D:\Project\AutoLedger\app\build\outputs\apk\debug\app-debug.apk"]))

    adb(["shell", "am", "force-stop", PKG], check=False)
    adb(["shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS"], check=False)
    # 冷启动后停在锁屏 / 快捷设置面板上，不解锁的话 am start 是白点的
    adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False)
    adb(["shell", "wm", "dismiss-keyguard"], check=False)
    adb(["shell", "input", "keyevent", "KEYCODE_HOME"], check=False)
    time.sleep(3)
    out = adb(["shell", "am", "start", "-W", "-n",
               "%s/com.local.autoledger.ui.MainActivity" % PKG], check=False)
    print("  " + out.replace("\n", " ")[:200])
    time.sleep(4)

    xml = dump()
    with open(r"D:\Project\AutoLedger\.workbuddy\ui_settings_0.xml", "w", encoding="utf-8") as f:
        f.write(xml)

    pos = None
    for attempt in range(4):
        pos = center(xml, "我的")
        if pos is not None:
            break
        print("第 %d 次没找到「我的」，界面上是：%s" % (attempt + 1, texts(xml)))
        if attempt < 3:
            adb(["shell", "am", "start", "-W", "-n",
                 "%s/com.local.autoledger.ui.MainActivity" % PKG], check=False)
            time.sleep(4)
            xml = dump()
    if pos is None:
        raise RuntimeError("底部导航里没找到「我的」")
    tap(*pos)
    time.sleep(2.5)
    return dump()


if __name__ == "__main__":
    sys.exit(main())
