#!/usr/bin/env python3
"""纯标准库生成 APK 图标（本机没有 Pillow，就直接手写 PNG）。

图形是「胶片格 + 播放三角」：一眼就是"动图/动起来"，
配色直接取网页里的 --brand(#3b6cf6) → --brand2(#7c5cf7) 渐变。

产物：
  res/mipmap-mdpi..xxxhdpi/ic_launcher.png        传统图标（渐变圆角底 + 胶片）
  res/mipmap-mdpi..xxxhdpi/ic_launcher_foreground.png  自适应图标前景（透明底、缩进安全区）
  （mipmap-anydpi-v26/ic_launcher.xml 与 values/colors.xml 手写固定，不由本脚本生成）

画法：每像素做 S×S 超采样再平均，等于自带抗锯齿，不依赖任何第三方库。
"""
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "android" / "res"

# 渐变两端
BRAND = (0x3B, 0x6C, 0xF6)
BRAND2 = (0x7C, 0x5C, 0xF7)
WHITE = (0xFF, 0xFF, 0xFF)
TRI = (0x6B, 0x55, 0xF6)      # 播放三角：比底色深一点，压在白色胶片上够清楚

S = 4                          # 超采样倍率


# ----------------------------------------------------------------- 几何判定

def rounded_rect(x, y, x0, y0, x1, y1, r):
    """点 (x,y) 是否落在圆角矩形内。r 为该矩形自身坐标系里的半径。"""
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    # 四条边以内的直区
    if x0 + r <= x <= x1 - r or y0 + r <= y <= y1 - r:
        return True
    # 四个圆角
    cx = x0 + r if x < x0 + r else x1 - r
    cy = y0 + r if y < y0 + r else y1 - r
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(x, y, ax, ay, bx, by, cx, cy):
    """同向叉积判定（含边界）。"""
    d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
    d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
    d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def gradient(x, y):
    """对角渐变：左上 BRAND → 右下 BRAND2。"""
    t = max(0.0, min(1.0, (x + y) / 2.0))
    return tuple(int(round(BRAND[i] + (BRAND2[i] - BRAND[i]) * t)) for i in range(3))


# ----------------------------------------------------------------- 图形

# 胶片区域（单位坐标）
FX0, FY0, FX1, FY1 = 0.13, 0.19, 0.87, 0.81
FR = 0.07
# 齿孔：一行 6 个，上下各一排
HOLE_W, HOLE_H = 0.058, 0.062
HOLE_R = 0.016
HOLES_CX = [0.200 + i * 0.120 for i in range(6)]
HOLE_TOP_CY = 0.268
HOLE_BOT_CY = 0.732
# 播放三角
TRI_VERTS = ((0.415, 0.330), (0.415, 0.670), (0.672, 0.500))


def in_hole(x, y):
    for cx in HOLES_CX:
        for cy in (HOLE_TOP_CY, HOLE_BOT_CY):
            if rounded_rect(x, y, cx - HOLE_W / 2, cy - HOLE_H / 2,
                            cx + HOLE_W / 2, cy + HOLE_H / 2, HOLE_R):
                return True
    return False


def sample(u, v, with_bg):
    """返回 (r,g,b,a)。u,v 为 0..1 的单位坐标。"""
    base = gradient(u, v) if with_bg else None

    # 背景圆角底（仅传统图标）
    if with_bg and not rounded_rect(u, v, 0.0, 0.0, 1.0, 1.0, 0.22):
        base = None

    # 胶片条
    if rounded_rect(u, v, FX0, FY0, FX1, FY1, FR):
        if in_hole(u, v):
            # 齿孔透出底色；自适应图标前景里是透明（露出系统给的纯色底）
            return (base + (255,)) if base else (0, 0, 0, 0)
        if in_triangle(u, v, *TRI_VERTS[0], *TRI_VERTS[1], *TRI_VERTS[2]):
            return TRI + (255,)
        return WHITE + (255,)

    return (base + (255,)) if base else (0, 0, 0, 0)


def render(size, with_bg, scale=1.0):
    """scale < 1 把图形缩到中心（自适应图标安全区用）。"""
    px = bytearray(size * size * 4)
    inv = 1.0 / (S * S)
    for j in range(size):
        for i in range(size):
            r = g = b = a = 0
            for sj in range(S):
                for si in range(S):
                    u = (i + (si + 0.5) / S) / size
                    v = (j + (sj + 0.5) / S) / size
                    if scale != 1.0:
                        u = (u - 0.5) / scale + 0.5
                        v = (v - 0.5) / scale + 0.5
                        if not (0.0 <= u <= 1.0 and 0.0 <= v <= 1.0):
                            continue
                    sr, sg, sb, sa = sample(u, v, with_bg)
                    if sa:
                        r += sr
                        g += sg
                        b += sb
                        a += sa
            o = (j * size + i) * 4
            if a:
                px[o] = r * 255 // a
                px[o + 1] = g * 255 // a
                px[o + 2] = b * 255 // a
                px[o + 3] = int(a * inv + 0.5)
            # 否则保持全 0（完全透明）
    return px


# ----------------------------------------------------------------- PNG 写出

def write_png(path: pathlib.Path, width: int, height: int, rgba: bytearray) -> None:
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)                      # 每行 filter 0
        raw.extend(rgba[y * stride:(y + 1) * stride])
    comp = zlib.compress(bytes(raw), 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", comp) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"  {path.relative_to(ROOT)}  {width}x{height}  {len(png)} B")


DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]


def main() -> None:
    print("生成图标：")
    for name, dp in DENSITIES:
        # 传统图标：48dp → 该密度对应像素
        px = dp
        write_png(RES / f"mipmap-{name}" / "ic_launcher.png", px, px,
                  render(px, with_bg=True))

    # 自适应图标前景：108dp，图形缩到中间 66% 安全区
    for name, dp in DENSITIES:
        px = int(dp * 108 / 48)
        write_png(RES / f"mipmap-{name}" / "ic_launcher_foreground.png", px, px,
                  render(px, with_bg=False, scale=0.88))


if __name__ == "__main__":
    main()
