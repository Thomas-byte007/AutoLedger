#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""启动器图标预览生成器。

把 res/drawable/ic_launcher_background.xml / ic_launcher_foreground.xml /
ic_launcher_monochrome.xml 里的几何在 PIL 里重画一遍，输出：

    dist/icon-preview/icon-512.png      方形原始画布（透明，可直接当应用商店图标素材）
    dist/icon-preview/启动器图标预览.png  一张对照图：完整画布 / 圆角方形 / Squircle / 圆形
                                        + 24~96px 小尺寸可读性 + 单色主题图标版

为什么要有这个脚本：矢量图标在小尺寸、被各家 launcher 裁成不同形状时最容易翻车，
改完 XML 跑一次就能肉眼确认；模拟器上装包看图标既慢又只能看到一种裁形。

用法（Windows 用系统 Python，自带 PIL）：
    C:\\Users\\<你>\\AppData\\Local\\Programs\\Python\\Python312\\python.exe tools\\preview_launcher_icon.py

注意：几何是手抄 XML 的，改 XML 后这里也要同步改，否则预览会骗人。
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "dist", "icon-preview")

R = 900          # 单张图标的渲染分辨率（大一点再缩，边缘更干净）
U = 108.0        # 矢量画布尺寸
K = R / U        # 单位换算


def label_font(size=17):
    """中文字体，缺失就退回默认（默认字体画不了中文，会变方块）"""
    for path in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf",
                 "/System/Library/Fonts/PingFang.ttc"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(c1, c2, t):
    return tuple(c1[i] + (c2[i] - c1[i]) * t for i in range(3))


def stops_at(t, stops):
    """stops = [(offset, color), ...]，线性插值取值"""
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    for i in range(len(stops) - 1):
        o1, c1 = stops[i]
        o2, c2 = stops[i + 1]
        if t <= o2:
            span = o2 - o1
            k = 0.0 if span <= 0 else (t - o1) / span
            return mix(c1, c2, k)
    return stops[-1][1]


def interp(t, stops):
    """标量版插值（给 alpha 用）"""
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    for i in range(len(stops) - 1):
        o1, v1 = stops[i]
        o2, v2 = stops[i + 1]
        if t <= o2:
            span = o2 - o1
            k = 0.0 if span <= 0 else (t - o1) / span
            return v1 + (v2 - v1) * k
    return stops[-1][1]


def u(x):
    """矢量单位 → 像素"""
    return x * K


def rline(d, p0, p1, width):
    """带圆头的线段：先画线，再在两端补圆（PIL 没有 round cap）"""
    d.line([(u(p0[0]), u(p0[1])), (u(p1[0]), u(p1[1]))], fill=255, width=int(round(u(width))))
    r = u(width) / 2.0
    for p in (p0, p1):
        x, y = u(p[0]), u(p[1])
        d.ellipse([x - r, y - r, x + r, y + r], fill=255)


def stroke_group(shapes, width, color, alpha=1.0):
    """把一批线段合成一个纯色图层（避免半透明重叠处颜色叠深）"""
    mask = Image.new("L", (R, R), 0)
    d = ImageDraw.Draw(mask)
    for s in shapes:
        rline(d, s[0], s[1], width)
    mask = mask.filter(ImageFilter.GaussianBlur(max(0.6, K * 0.25)))
    if alpha < 1.0:
        mask = mask.point(lambda v: int(v * alpha))
    layer = Image.new("RGBA", (R, R), color + (255,))
    layer.putalpha(mask)
    return layer


def paste(img, layer):
    img.alpha_composite(layer)


def build_background():
    """蓝色渐变底板 + 左上角柔光"""
    img = Image.new("RGBA", (R, R))
    base = [(0.0, rgb("#5C8CFF")), (0.55, rgb("#3B6CF6")), (1.0, rgb("#244FCB"))]
    white = (255, 255, 255)
    cx, cy, rad = u(0.22 * U), u(0.16 * U), u(0.82 * U)
    px = img.load()
    for y in range(R):
        v = y / K
        for x in range(R):
            uu = x / K
            c = stops_at((uu + v) / (2 * U), base)
            d = math.hypot(x - cx, y - cy) / rad
            if d < 1.0:
                a = interp(d, [(0.0, 89.0), (0.55, 20.0), (1.0, 0.0)]) / 255.0
                c = mix(c, white, a)
            px[x, y] = (int(c[0]), int(c[1]), int(c[2]), 255)
    return img


def build_foreground():
    """账单卡片 + ¥ + 账目行 + 金币（透明底，叠在底板之上）"""
    img = Image.new("RGBA", (R, R), (0, 0, 0, 0))

    # 卡片：圆角矩形 + 竖向渐变
    box = [(u(30), u(27)), (u(78), u(81))]
    card_mask = Image.new("L", (R, R), 0)
    ImageDraw.Draw(card_mask).rounded_rectangle(box, radius=u(14), fill=255)
    card = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    top, bottom = rgb("#FFFFFF"), rgb("#E9EFFF")
    y0, y1 = int(u(27)), int(u(81))
    for y in range(y0, y1):
        c = mix(top, bottom, (y / K - 27.0) / 54.0)
        d.line([(u(30), y), (u(78), y)], fill=(int(c[0]), int(c[1]), int(c[2]), 255))
    card.putalpha(card_mask.filter(ImageFilter.GaussianBlur(max(0.6, K * 0.25))))
    paste(img, card)

    # 账目行
    paste(img, stroke_group(
        [((40, 66), (52, 66)), ((40, 74), (51, 74))], 3.4, rgb("#2A55D4"), alpha=0.30))

    # ¥：V 形 + 竖笔
    paste(img, stroke_group(
        [((38, 36), (49, 47)), ((60, 36), (49, 47)), ((49, 47), (49, 59))], 4.4, rgb("#2A55D4")))
    # ¥：两横
    paste(img, stroke_group([((38, 51), (60, 51)), ((38, 56.5), (59, 56.5))], 4.0, rgb("#2A55D4")))

    # 金币：渐变圆 + 内圈高光
    coin = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    cbox = [(u(55), u(57)), (u(75), u(77))]
    cg = [(0.0, rgb("#FFE3A6")), (1.0, rgb("#EFA133"))]
    cd = ImageDraw.Draw(coin)
    for y in range(int(u(57)), int(u(77)) + 1):
        v = y / K
        c = stops_at(((v - 57.0) / 20.0), cg)
        cd.line([(u(55), y), (u(75), y)], fill=(int(c[0]), int(c[1]), int(c[2]), 255))
    cmask = Image.new("L", (R, R), 0)
    ImageDraw.Draw(cmask).ellipse([(u(55), u(57)), (u(75), u(77))], fill=255)
    coin.putalpha(cmask.filter(ImageFilter.GaussianBlur(max(0.6, K * 0.25))))
    paste(img, coin)

    ring = Image.new("L", (R, R), 0)
    rd = ImageDraw.Draw(ring)
    rd.ellipse([(u(58.2), u(60.2)), (u(71.8), u(73.8))], outline=255, width=int(round(u(1.4))))
    ring = ring.filter(ImageFilter.GaussianBlur(max(0.5, K * 0.2))).point(lambda v: int(v * 0.55))
    rl = Image.new("RGBA", (R, R), (255, 255, 255, 255))
    rl.putalpha(ring)
    paste(img, rl)
    return img


def build_monochrome(color):
    """单色（主题图标）版：卡片轮廓 + ¥ + 金币轮廓"""
    img = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    box = [(u(32), u(29)), (u(76), u(79))]

    outline = Image.new("L", (R, R), 0)
    ImageDraw.Draw(outline).rounded_rectangle(box, radius=u(12), outline=255,
                                              width=int(round(u(4.4))))
    ol = Image.new("RGBA", (R, R), color + (255,))
    ol.putalpha(outline.filter(ImageFilter.GaussianBlur(max(0.6, K * 0.25))))
    paste(img, ol)

    paste(img, stroke_group(
        [((41, 66), (51, 66)), ((41, 73), (50, 73))], 3.4, color, alpha=0.75))
    paste(img, stroke_group(
        [((39, 38), (49.5, 48.5)), ((60, 38), (49.5, 48.5)), ((49.5, 48.5), (49.5, 60))], 4.4, color))
    paste(img, stroke_group([((39, 52.5), (60, 52.5)), ((39, 56.5), (58, 56.5))], 4.0, color))

    ring = Image.new("L", (R, R), 0)
    ImageDraw.Draw(ring).ellipse([(u(57), u(59)), (u(71), u(73))],
                                 outline=255, width=int(round(u(3.6))))
    rl = Image.new("RGBA", (R, R), color + (255,))
    rl.putalpha(ring.filter(ImageFilter.GaussianBlur(max(0.6, K * 0.25))))
    paste(img, rl)
    return img


def mask_rounded(radius_ratio):
    m = Image.new("L", (R, R), 0)
    ImageDraw.Draw(m).rounded_rectangle([(0, 0), (R - 1, R - 1)], radius=R * radius_ratio, fill=255)
    return m.filter(ImageFilter.GaussianBlur(R * 0.004))


def mask_circle():
    m = Image.new("L", (R, R), 0)
    ImageDraw.Draw(m).ellipse([(0, 0), (R - 1, R - 1)], fill=255)
    return m.filter(ImageFilter.GaussianBlur(R * 0.004))


def mask_squircle(n=4.0):
    """超椭圆，逼近 Android 的 squircle 裁形"""
    m = Image.new("L", (R, R), 0)
    px = m.load()
    c = (R - 1) / 2.0
    for y in range(R):
        ny = abs((y - c) / c)
        for x in range(R):
            nx = abs((x - c) / c)
            px[x, y] = 255 if nx ** n + ny ** n <= 1.0 else 0
    return m.filter(ImageFilter.GaussianBlur(R * 0.004))


def masked(icon, mask, size):
    m = mask.resize((size, size), Image.LANCZOS)
    i = icon.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(i, (0, 0), m)
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    bg = build_background()
    fg = build_foreground()
    icon = bg.copy()
    icon.alpha_composite(fg)

    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT_DIR, "icon-512.png"))

    mono = build_monochrome(rgb("#1B2540"))

    page = rgb("#F4F6FB")
    card = (255, 255, 255)
    pad = 36
    big = 250
    gap = 28
    small_sizes = [24, 40, 56, 72, 96]
    row2_h = 150
    row3_h = big + 74

    w = pad * 2 + big * 4 + gap * 3
    h = pad + 34 + big + 26 + row2_h + 26 + row3_h + pad
    sheet = Image.new("RGB", (w, h), page)
    d = ImageDraw.Draw(sheet)
    font = label_font()

    masks = [
        ("完整画布", None),
        ("圆角方形", mask_rounded(0.22)),
        ("Squircle", mask_squircle()),
        ("圆形", mask_circle()),
    ]
    x = pad
    y = pad + 34
    for name, mk in masks:
        tile = Image.new("RGBA", (big, big), card + (255,))
        if mk is None:
            tile.alpha_composite(icon.resize((big, big), Image.LANCZOS))
        else:
            tile.alpha_composite(masked(icon, mk, big))
        sheet.paste(tile.convert("RGB"), (x, y))
        d.text((x + 2, y - 24), name, fill=rgb("#5A6478"), font=font)
        x += big + gap

    # 小尺寸可读性
    y2 = pad + 34 + big + 26
    d.text((pad + 2, y2 - 24), "小尺寸可读性（Squircle 裁形）：24 / 40 / 56 / 72 / 96 px",
           fill=rgb("#5A6478"), font=font)
    panel = Image.new("RGB", (w - pad * 2, row2_h), card)
    ImageDraw.Draw(panel).rounded_rectangle([(0, 0), (w - pad * 2 - 1, row2_h - 1)],
                                            radius=18, fill=card)
    px = 26
    sq = mask_squircle()
    for s in small_sizes:
        t = masked(icon, sq, s)
        panel.paste(t.convert("RGB"), (px, (row2_h - s) // 2), t)
        px += s + 34
    sheet.paste(panel, (pad, y2))

    # 单色主题图标
    y3 = y2 + row2_h + 26
    d.text((pad + 2, y3 - 24), "单色主题图标（Android 13+ Themed Icons）",
           fill=rgb("#5A6478"), font=font)
    x = pad
    for name, mk in [("Squircle", mask_squircle()), ("圆形", mask_circle())]:
        tile = Image.new("RGBA", (big, big), card + (255,))
        tile.alpha_composite(masked(mono, mk, big))
        sheet.paste(tile.convert("RGB"), (x, y3))
        x += big + gap

    out = os.path.join(OUT_DIR, "启动器图标预览.png")
    sheet.save(out)
    print("已生成：")
    print("  " + os.path.join(OUT_DIR, "icon-512.png"))
    print("  " + out)


if __name__ == "__main__":
    main()
