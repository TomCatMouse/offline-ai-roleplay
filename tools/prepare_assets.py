# -*- coding: utf-8 -*-
"""
把 asset 目录里的成品图处理成 App 直接可用的资源。

做四件事：
  1. 抠掉白色背景 —— 从四边泛洪填充，只删与边界连通的近白区域，
     这样衣服/高光里的白色不会被误伤（素材构图有圆形、圆角方形、满幅三种）。
  2. 生成启动图标 —— 自适应图标前景层 + 各密度传统 PNG + monochrome 剪影。
  3. 生成角色头像 —— 圆形裁切、去背景、256x256 PNG。
  4. 生成对照预览图 —— 方便人工确认哪张图配哪个角色。

用法：
    python prepare_assets.py            # 全部处理
    python prepare_assets.py --dry-run  # 只分析不写文件
"""
import os
import glob
import argparse
import numpy as np
import cv2
from PIL import Image, ImageDraw

# ─────────────────────────── 路径 ───────────────────────────
# 用脚本自身位置推导项目根目录，换机器不换路径
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 素材目录。原先目录名末尾带一个不可见的零宽字符 U+200C，
# 会让每次路径操作都出问题，已重命名为干净的 asset。
ASSET_DIR = os.path.join(PROJECT, 'asset')
RES_DIR = os.path.join(PROJECT, 'app', 'src', 'main', 'res')
DRAWABLE_NODPI = os.path.join(RES_DIR, 'drawable-nodpi')
EVIDENCE_DIR = os.path.join(PROJECT, 'docs', 'evidence')

APP_ICON_SOURCE = '图标3.jpg'

# 传统图标各密度边长（px）：48dp 基准
LEGACY_SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
# 自适应图标各密度画布边长（px）：108dp 基准
ADAPTIVE_SIZES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
# 自适应图标安全区：内容直径占画布的 66/108
SAFE_ZONE_RATIO = 66.0 / 108.0

AVATAR_PX = 256


# ─────────────────────── 背景去除 ───────────────────────
def strip_white_background(rgb, tol_lo=226, tol_sat=22):
    """
    从四边泛洪填充去除白背景。

    只删除"与图像边界连通"的近白区域，所以：
      · 圆形构图的四角留白  -> 删掉
      · 圆角方形外侧的白边  -> 删掉
      · 角色衣服/高光里的白 -> 保留（不与边界连通）

    返回 (rgba, bg_mask)：rgba 为 uint8 HxWx4，bg_mask 为被判定为背景的布尔图。
    """
    mn = rgb.min(axis=2).astype(np.int16)
    mx = rgb.max(axis=2).astype(np.int16)
    cand = ((mn > tol_lo) & ((mx - mn) < tol_sat)).astype(np.uint8)

    n, labels = cv2.connectedComponents(cand, 8)
    h, w = cand.shape
    border_labels = set(labels[0, :]) | set(labels[-1, :]) | set(labels[:, 0]) | set(labels[:, -1])
    border_labels.discard(0)
    bg_mask = np.isin(labels, list(border_labels))

    alpha = np.where(bg_mask, 0, 255).astype(np.uint8)

    # 边缘羽化：对 alpha 做一次小半径模糊，消除锯齿
    alpha = cv2.GaussianBlur(alpha, (3, 3), 0.8)
    # 模糊会把纯背景抬起来一点，重新压回 0
    alpha[bg_mask & (alpha < 40)] = 0

    rgba = np.dstack([rgb.astype(np.uint8), alpha])
    return rgba, bg_mask


def crop_to_content(rgba, pad_ratio=0.04, square=True):
    """按 alpha 通道裁到内容包围盒，可选外扩成正方形（头像需要）。"""
    alpha = rgba[:, :, 3]
    ys, xs = np.where(alpha > 12)
    if len(ys) == 0:
        return rgba
    x1, x2, y1, y2 = xs.min(), xs.max(), ys.min(), ys.max()

    if square:
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        half = max(x2 - x1, y2 - y1) / 2.0
        pad = half * pad_ratio
        half += pad
        x1, x2 = int(cx - half), int(cx + half)
        y1, y2 = int(cy - half), int(cy + half)

    h, w = alpha.shape
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2 + 1), min(h, y2 + 1)
    return rgba[y1:y2, x1:x2]


def circular_mask(size, ss=4):
    """超采样生成抗锯齿的圆形 alpha 掩码，返回 float32 0..1。"""
    big = Image.new('L', (size * ss, size * ss), 0)
    d = ImageDraw.Draw(big)
    d.ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    small = big.resize((size, size), Image.LANCZOS)
    return np.asarray(small).astype(np.float32) / 255.0


def edge_color(rgba_rgb, alpha, r_lo=0.82, r_hi=255.0):
    """取圆形内容最外圈的平均颜色，用作自适应图标背景色，可与前景无缝衔接。"""
    h, w = alpha.shape
    yy, xx = np.mgrid[0:h, 0:w]
    cy, cx = h / 2.0, w / 2.0
    rr = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
    scale = min(h, w) / 2.0
    ring = (rr > scale * r_lo) & (rr <= min(scale, r_hi)) & (alpha > 200)
    if ring.sum() < 50:
        ring = alpha > 200
    return rgba_rgb[ring].mean(axis=0).astype(int)


# ─────────────────────── 输出：启动图标 ───────────────────────
def build_launcher_icon(dry=False):
    src = os.path.join(ASSET_DIR, APP_ICON_SOURCE)
    rgb = np.asarray(Image.open(src).convert('RGB'))
    rgba, bg_mask = strip_white_background(rgb)
    print('  [图标] 去背景 %.1f%%，内容保留 %.1f%%' % (100 * bg_mask.mean(), 100 * (~bg_mask).mean()))

    cropped = crop_to_content(rgba, pad_ratio=0.0, square=True)
    h, w = cropped.shape[:2]
    scale = min(h, w) / 2.0
    bg_rgb = edge_color(cropped[:, :, :3], cropped[:, :, 3])
    print('  [图标] 边缘色 = #%02X%02X%02X -> 用作自适应背景' % tuple(bg_rgb))

    # 圆形裁切后的方形素材（原始尺寸）
    mask = circular_mask(max(h, w))
    mask = mask[:h, :w]
    base = cropped.copy()
    base[:, :, 3] = (base[:, :, 3].astype(np.float32) * mask).astype(np.uint8)
    base_img = Image.fromarray(base, 'RGBA')

    if dry:
        return bg_rgb

    os.makedirs(DRAWABLE_NODPI, exist_ok=True)

    # ---- 1) 自适应图标前景层：内容缩到安全区 66/108 ----
    for dens, canvas in ADAPTIVE_SIZES.items():
        inner = int(round(canvas * SAFE_ZONE_RATIO))
        art = base_img.resize((inner, inner), Image.LANCZOS)
        fg = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
        off = (canvas - inner) // 2
        fg.paste(art, (off, off), art)
        d = os.path.join(RES_DIR, 'mipmap-' + dens)
        os.makedirs(d, exist_ok=True)
        fg.save(os.path.join(d, 'ic_launcher_foreground.png'))

    # ---- 2) monochrome 层：用 alpha 做剪影（供 Android 13 主题图标）----
    for dens, canvas in ADAPTIVE_SIZES.items():
        inner = int(round(canvas * SAFE_ZONE_RATIO))
        a = np.asarray(base_img.resize((inner, inner), Image.LANCZOS))[:, :, 3]
        mono = np.zeros((canvas, canvas, 4), dtype=np.uint8)
        off = (canvas - inner) // 2
        mono[off:off + inner, off:off + inner, :3] = 255
        mono[off:off + inner, off:off + inner, 3] = a
        d = os.path.join(RES_DIR, 'mipmap-' + dens)
        Image.fromarray(mono, 'RGBA').save(os.path.join(d, 'ic_launcher_monochrome.png'))

    # ---- 3) 传统图标：方形（圆角）+ 圆形，内容铺满 ----
    for dens, size in LEGACY_SIZES.items():
        d = os.path.join(RES_DIR, 'mipmap-' + dens)
        os.makedirs(d, exist_ok=True)
        art = base_img.resize((size, size), Image.LANCZOS)

        # 方形：背景色打底 + 圆角
        square = Image.new('RGBA', (size, size), tuple(bg_rgb) + (255,))
        square.paste(art, (0, 0), art)
        rad = int(size * 0.22)
        rmask = Image.new('L', (size * 4, size * 4), 0)
        ImageDraw.Draw(rmask).rounded_rectangle(
            (0, 0, size * 4 - 1, size * 4 - 1), radius=rad * 4, fill=255)
        square.putalpha(rmask.resize((size, size), Image.LANCZOS))
        square.save(os.path.join(d, 'ic_launcher.png'))

        # 圆形
        rnd = Image.new('RGBA', (size, size), tuple(bg_rgb) + (255,))
        rnd.paste(art, (0, 0), art)
        cm = Image.fromarray((circular_mask(size) * 255).astype(np.uint8), 'L')
        rnd.putalpha(cm)
        rnd.save(os.path.join(d, 'ic_launcher_round.png'))

    print('  [图标] 已写出 5 档密度：前景层 + monochrome + 方形 + 圆形')
    return bg_rgb


# ─────────────────────── 输出：角色头像 ───────────────────────
def build_avatars(dry=False):
    files = sorted(glob.glob(os.path.join(ASSET_DIR, '*.jpg')))
    others = [f for f in files if os.path.basename(f) != APP_ICON_SOURCE]
    print('  [头像] 素材 %d 张：%s' % (len(others), ', '.join(os.path.basename(f) for f in others)))

    if not dry:
        os.makedirs(DRAWABLE_NODPI, exist_ok=True)

    out = []
    for i, f in enumerate(others, start=1):
        rgb = np.asarray(Image.open(f).convert('RGB'))
        rgba, bg_mask = strip_white_background(rgb)
        cropped = crop_to_content(rgba, pad_ratio=0.06, square=True)

        h, w = cropped.shape[:2]
        side = max(h, w)
        canvas = np.zeros((side, side, 4), dtype=np.uint8)
        canvas[(side - h) // 2:(side - h) // 2 + h, (side - w) // 2:(side - w) // 2 + w] = cropped

        mask = circular_mask(side)
        canvas[:, :, 3] = (canvas[:, :, 3].astype(np.float32) * mask).astype(np.uint8)

        img = Image.fromarray(canvas, 'RGBA').resize((AVATAR_PX, AVATAR_PX), Image.LANCZOS)
        name = 'avatar_%d.png' % i
        if not dry:
            img.save(os.path.join(DRAWABLE_NODPI, name))
        inner_rgb = canvas[canvas[:, :, 3] > 200][:, :3].mean(axis=0).astype(int)
        out.append((i, os.path.basename(f), name, 100 * bg_mask.mean(), inner_rgb))
        print('    #%d  %-12s -> %-14s 去背景 %4.1f%%  主色 #%02X%02X%02X'
              % (i, os.path.basename(f), name, 100 * bg_mask.mean(), *inner_rgb))

    return out


# ─────────────────────── 输出：对照预览图 ───────────────────────
def build_contact_sheet(entries, dry=False):
    """把处理后的头像 + 图标排成一张带编号的预览图，方便人工确认男女归属。"""
    if dry or not entries:
        return
    cell = 220
    cols = 4
    rows = (len(entries) + 1 + cols - 1) // cols
    pad = 16
    W = cols * cell + (cols + 1) * pad
    H = rows * (cell + 40) + (rows + 1) * pad

    sheet = Image.new('RGB', (W, H), (24, 24, 32))
    d = ImageDraw.Draw(sheet)

    items = [(str(i), name) for (i, _src, name, _bg, _c) in entries]
    items.append(('图标', APP_ICON_SOURCE))

    for idx, (label, name) in enumerate(items):
        r, c = divmod(idx, cols)
        x = pad + c * (cell + pad)
        y = pad + r * (cell + 40 + pad)
        # 图标预览放证据目录（不进 APK），头像是真实资源
        if label == '图标':
            p = os.path.join(EVIDENCE_DIR, '_app_icon_preview.png')
        else:
            p = os.path.join(DRAWABLE_NODPI, 'avatar_%s.png' % label)
        if not os.path.exists(p):
            continue
        art = Image.open(p).convert('RGBA').resize((cell, cell), Image.LANCZOS)
        sheet.paste(art, (x, y), art)
        d.text((x + 6, y + cell + 10), 'No.%s  %s' % (label, name), fill=(240, 240, 250))

    os.makedirs(EVIDENCE_DIR, exist_ok=True)
    out = os.path.join(EVIDENCE_DIR, 'avatar_contact_sheet.png')
    sheet.save(out)
    print('  [预览] 已写出 %s' % out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()
    dry = args.dry_run

    print('=' * 70)
    print('  处理素材%s' % ('（试运行，不写文件）' if dry else ''))
    print('=' * 70)

    print('\n[1/3] 启动图标')
    build_launcher_icon(dry)

    print('\n[2/3] 角色头像')
    entries = build_avatars(dry)

    print('\n[3/3] 对照预览图')
    # 预览图也需要图标素材，先单独导出一张
    if not dry:
        src = os.path.join(ASSET_DIR, APP_ICON_SOURCE)
        rgb = np.asarray(Image.open(src).convert('RGB'))
        rgba, _ = strip_white_background(rgb)
        cr = crop_to_content(rgba, 0.0, True)
        h, w = cr.shape[:2]
        side = max(h, w)
        cv_ = np.zeros((side, side, 4), dtype=np.uint8)
        cv_[(side - h) // 2:(side - h) // 2 + h, (side - w) // 2:(side - w) // 2 + w] = cr
        cv_[:, :, 3] = (cv_[:, :, 3].astype(np.float32) * circular_mask(side)).astype(np.uint8)
        Image.fromarray(cv_, 'RGBA').resize((256, 256), Image.LANCZOS).save(
            os.path.join(EVIDENCE_DIR, '_app_icon_preview.png'))
    build_contact_sheet(entries, dry)

    print('\n完成。')


if __name__ == '__main__':
    main()
