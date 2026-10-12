# -*- coding: utf-8 -*-
"""
验证 App 里角色头像的「映射是否正确」。

思路：截一张角色列表的图，用 uiautomator 给出的每个头像节点的坐标
裁出屏幕上的头像，再和 res/drawable-nodpi/avatar_*.png 逐张做相关性比对，
看屏幕上实际显示的是哪一张素材。

这样能一次性证明两件事：
  1. 立绘真的渲染出来了（不是回退到 Q 版头像，也不是空白）
  2. 角色名 -> 素材编号的映射和代码里写的一致

用法：
    python verify_avatar_mapping.py <截图> <头像坐标json>
"""
import os
import json
import sys
import numpy as np
from PIL import Image

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AVATAR_DIR = os.path.join(PROJECT, 'app', 'src', 'main', 'res', 'drawable-nodpi')

# 代码里声明的映射（与 AvatarGenerator.kt 的 characterArtRes 保持一致）
# 男性角色 = 张学长 / 墨尘 -> 素材 图标5.jpg(avatar_5) / 图标6.jpg(avatar_6)
EXPECTED = {
    '林小夏': 1, '苏婉儿': 2, '沈清秋': 3, '星野铃': 7,
    '张学长': 5, '墨尘': 6,
}


def central_disc(shape, ratio=0.62):
    """只取头像中心区域做比对。

    外圈被「稀有度描边」覆盖、右下角被「等级点」覆盖，都会干扰相关性，
    裁掉它们只看中心，比出来的结果才干净。
    """
    h, w = shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    cy, cx = (h - 1) / 2.0, (w - 1) / 2.0
    rr = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
    return rr <= (min(h, w) / 2.0) * ratio


def main():
    shot_path, pos_path = sys.argv[1], sys.argv[2]
    shot = np.asarray(Image.open(shot_path).convert('RGB')).astype(np.float32)
    positions = json.load(open(pos_path, encoding='utf-8-sig'))
    # 只有一个节点时 ConvertTo-Json 输出的是对象而不是数组，统一成列表
    if isinstance(positions, dict):
        positions = [positions]

    candidates = {}
    for i in range(1, 10):
        p = os.path.join(AVATAR_DIR, 'avatar_%d.png' % i)
        if os.path.exists(p):
            candidates[i] = Image.open(p).convert('RGBA')

    print('=' * 74)
    print('  头像映射验证   截图 %s' % os.path.basename(shot_path))
    print('=' * 74)
    print('  %-8s %-9s %-9s %-9s %s' % ('角色', '期望素材', '实测最佳', '相关系数', '结论'))
    print('  ' + '-' * 68)

    all_ok = True
    for pos in positions:
        name = pos['name']
        x1, y1, x2, y2 = pos['x1'], pos['y1'], pos['x2'], pos['y2']
        h, w = y2 - y1, x2 - x1
        if h < 40 or w < 40:
            print('  %-8s %-9s %-9s %-9s 跳过（节点被屏幕裁切，只剩 %dx%d）' % (name, EXPECTED.get(name, '?'), '-', '-', w, h))
            continue

        crop = shot[y1:y2, x1:x2]
        mask = central_disc(crop.shape)
        target = crop[mask]

        best, best_r = None, -2.0
        for idx, img in candidates.items():
            ref = np.asarray(img.resize((w, h), Image.LANCZOS).convert('RGB')).astype(np.float32)
            v = ref[mask]
            # 皮尔逊相关系数
            a = target - target.mean()
            b = v - v.mean()
            denom = np.sqrt((a * a).sum() * (b * b).sum())
            r = float((a * b).sum() / denom) if denom > 1e-6 else 0.0
            if r > best_r:
                best, best_r = idx, r

        exp = EXPECTED.get(name)
        ok = (best == exp) and best_r > 0.5
        all_ok &= ok
        print('  %-8s %-9s %-9s %-9.3f %s' % (
            name, 'avatar_%s' % exp, 'avatar_%s' % best, best_r,
            '一致 OK' if ok else '不一致 NG'))

    print()
    print('  总体: %s' % ('全部一致，映射正确' if all_ok else '存在不一致，需要核对'))


if __name__ == '__main__':
    main()
