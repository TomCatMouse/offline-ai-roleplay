# -*- coding: utf-8 -*-
"""
逐帧分析录屏，客观检测"滚动时头像一闪一闪的"。

两种互补的指标：

  1) 肤色像素数
     头像的脸是暖肤色，全屏最容易分离的暖色区域。
     小幅来回滑动时，同一批头像一直留在屏幕上，数值应当基本恒定。

  2) 闪现检测（A -> B -> A 三帧模式）—— 这个才是关键
     真实滚动时画面是平滑位移的：F[i-1] 与 F[i+1] 的差异 ≈ 2 倍单帧差异。
     而"闪一下"的特征是：中间帧 F[i] 与前后都差异很大，
     但 F[i-1] 与 F[i+1] 几乎一模一样（因为闪完又回来了）。
     所以用  skip / min(prev,next)  这个比值来抓：
        比值接近 1  -> 正常平滑移动
        比值接近 0  -> 中间帧是"冒出来又消失"的闪现

用法：
  python tools/avatar_flicker_analysis.py <帧目录> [--crop-top 235] [--label xxx]
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image

# 分析用的缩放尺寸（越小越快，只关心大块区域变化）
ANALYSIS_W, ANALYSIS_H = 180, 320


def skin_mask(arr):
    """暖肤色掩码：亮 + 暖（R 明显大于 B）"""
    r = arr[:, :, 0].astype(np.int16)
    g = arr[:, :, 1].astype(np.int16)
    b = arr[:, :, 2].astype(np.int16)
    return (r > 222) & (g > 180) & (g < 250) & ((r - b) >= 24) & ((r - g) >= 4) & ((r - g) <= 60)


def load_frames(paths, crop_top, full_res=True):
    skins, grays = [], []
    for p in paths:
        im = Image.open(p).convert("RGB")
        arr = np.asarray(im)
        if crop_top > 0:
            arr = arr[crop_top:, :, :]
        if full_res:
            skins.append(int(skin_mask(arr).sum()))
        small = np.asarray(Image.fromarray(arr).resize((ANALYSIS_W, ANALYSIS_H), Image.BILINEAR))
        grays.append(small.mean(axis=2).astype(np.float32))
    return np.array(skins, dtype=np.float64), np.stack(grays)


def blink_analysis(grays):
    """返回每帧的 skip/min(prev,next) 比值。越小越像"闪现"。"""
    n = len(grays)
    if n < 3:
        return np.ones(max(n, 1)), np.zeros(max(n, 1)), np.zeros(max(n, 1))
    # 对每个"中间帧" i（1 <= i <= n-2）比较：i 与前后帧、以及前后帧彼此
    d_prev = np.array([np.abs(grays[i] - grays[i - 1]).mean() for i in range(1, n - 1)])
    d_next = np.array([np.abs(grays[i + 1] - grays[i]).mean() for i in range(1, n - 1)])
    d_skip = np.array([np.abs(grays[i + 1] - grays[i - 1]).mean() for i in range(1, n - 1)])
    denom = np.minimum(d_prev, d_next)
    ratio = np.where(denom > 0.3, d_skip / np.maximum(denom, 1e-6), np.nan)
    return ratio, d_prev, d_skip


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames")
    ap.add_argument("--crop-top", type=int, default=235)
    ap.add_argument("--label", default="")
    ap.add_argument("--blink-ratio", type=float, default=0.45,
                    help="skip/min(prev,next) 低于该值即判定为闪现帧")
    ap.add_argument("--blink-motion", type=float, default=1.2,
                    help="单帧差异低于该值说明画面几乎没动，不算闪现")
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(args.frames, "*.png")))
    if not paths:
        print("找不到帧文件: %s" % args.frames)
        return 1

    skins, grays = load_frames(paths, args.crop_top)
    n = len(skins)
    med = float(np.median(skins))

    ratio, d_prev, d_skip = blink_analysis(grays)

    # 闪现帧：前后帧几乎一样，但确实有位移量
    blinks = []
    for k in range(len(ratio)):
        r = ratio[k]
        if np.isnan(r):
            continue
        if r < args.blink_ratio and d_prev[k] >= args.blink_motion:
            blinks.append((k + 1, round(float(r), 3), round(float(d_prev[k]), 2),
                           int(skins[k + 1]), int(skins[k + 1 - 1])))

    # 肤色骤降
    dips = [int(i) for i in np.where(skins < 0.60 * med)[0]] if med > 0 else []

    print("=" * 72)
    if args.label:
        print("  %s" % args.label)
    print("=" * 72)
    print("  帧数: %d" % n)
    print("  肤色像素 中位 %.1f | 均值 %.1f | 最小 %.0f (%.1f%%) | 最大 %.0f"
          % (med, skins.mean(), skins.min(),
             100.0 * skins.min() / med if med else 0, skins.max()))
    print("  肤色骤降帧(<中位60%%): %d 帧" % len(dips))
    valid = ratio[~np.isnan(ratio)]
    if len(valid):
        print("  闪烁比值 skip/min(prev,next): 中位 %.3f | 最小 %.3f | <%.2f 的帧 %d 个"
              % (np.median(valid), valid.min(), args.blink_ratio, int((valid < args.blink_ratio).sum())))
    print("-" * 72)
    print("  ★ 闪现帧（A->B->A，画面闪了一下又回来）: %d 个" % len(blinks))
    if blinks:
        print("     帧序  比值   位移量  该帧肤色  前一帧肤色")
        for b in blinks[:25]:
            print("     %5d  %.3f  %7.2f  %8d  %10d" % b)
        print("     ...（最多显示 25 个）" if len(blinks) > 25 else "")
    print("=" * 72)

    if len(blinks) == 0:
        print("  判定: 未检测到闪烁")
        v = "PASS"
    elif len(blinks) <= max(2, int(0.01 * n)):
        print("  判定: 偶发闪现（%d 个）" % len(blinks))
        v = "MILD"
    else:
        print("  判定: 存在明显闪烁（%d 个闪现帧, 占 %.1f%%）"
              % (len(blinks), 100.0 * len(blinks) / n))
        v = "FLICKER"
    print("  VERDICT=%s" % v)
    return 0


if __name__ == "__main__":
    sys.exit(main())
