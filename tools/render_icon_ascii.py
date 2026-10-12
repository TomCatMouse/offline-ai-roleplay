"""
把成品图渲染成"颜色分类字符画"，用于在无法直接看图时判断画面内容。

为什么不用普通灰度字符画：动漫立绘的脸、头发、衣服在灰度上很容易糊成一片，
但它们的**色相/饱和度差异**非常明显。所以这里按颜色类别出图：

    .  背景（近白）
    S  皮肤（暖色调、亮）
    H  深色头发 / 深色线条
    h  浅色头发（银白/浅金/浅粉等，亮但带色）
    C  高饱和衣物或配饰
    c  低饱和衣物（灰、米、浅蓝等）
    W  纯白高光/留白
    *  其他

用法：python render_icon_ascii.py <图片路径> [列数]
"""
import sys
import numpy as np
from PIL import Image


def classify(a):
    """返回颜色类别码图。a 形状 (H, W, 3)，取值 0-255 的 int16。"""
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    mx = a.max(axis=2)
    mn = a.min(axis=2)
    sat = mx - mn
    lum = (0.299 * r + 0.587 * g + 0.114 * b)

    out = np.full(a.shape[:2], '*', dtype='<U1')

    # 纯白 / 背景
    out[(mn > 232) & (sat < 18)] = '.'

    # 暖色亮部 = 皮肤
    skin = (r > 195) & (g > 150) & (b > 120) & ((r - b) > 22) & ((r - g) > 6) & ((r - g) < 75)
    out[skin & (out == '*')] = 'S'

    # 深色（头发、线条、深色衣物）
    out[(lum < 115) & (out == '*')] = 'H'

    # 浅色带色相 = 浅色头发（银白/浅金/浅粉/浅蓝）
    light_hair = (lum >= 170) & (sat >= 18) & (sat < 90) & (out == '*')
    out[light_hair] = 'h'

    # 高饱和 = 衣物/配饰
    out[(sat >= 90) & (out == '*')] = 'C'

    # 其余偏灰的中间调 = 低饱和衣物
    mid = (sat < 90) & (lum >= 115) & (lum < 170) & (out == '*')
    out[mid] = 'c'

    return out


def render(path, cols=76):
    img = Image.open(path).convert('RGB')
    w0, h0 = img.size
    rows = max(1, int(round(cols * (h0 / w0) / 2.0)))   # 字符高宽比约 2:1

    small = np.asarray(img.resize((cols, rows), Image.LANCZOS)).astype(np.int16)
    codes = classify(small)

    print('=' * (cols + 4))
    print('  %s   %dx%d' % (path.split('\\')[-1], w0, h0))
    print('=' * (cols + 4))
    for row in codes:
        print('  ' + ''.join(row))
    print()


if __name__ == '__main__':
    p = sys.argv[1]
    c = int(sys.argv[2]) if len(sys.argv) > 2 else 76
    render(p, c)
