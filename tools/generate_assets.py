# -*- coding: utf-8 -*-
"""
兜兜X 资源生成脚本：
1. 吉祥物白底抠图（边缘 flood-fill，保留白色身体）-> drawable-nodpi/img_doudou.png
2. 圆形头像变体 -> drawable-nodpi/img_doudou_circle.png
3. 启动图标：裁剪白边 -> 各密度 legacy / round / foreground
4. 采样图标渐变主色，供 background drawable 使用
"""
import os
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
# 原始素材放在项目 assets/ 目录，或用环境变量/命令行参数指定任意路径
MASCOT_SRC = os.environ.get("MASCOT_SRC") or os.path.join(ROOT, "assets", "doudou_mascot.png")
ICON_SRC = os.environ.get("ICON_SRC") or os.path.join(ROOT, "assets", "ic_launcher_source.png")

DENSITIES = {  # density -> (legacy icon px, adaptive foreground px)
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def dilate(m):
    d = m.copy()
    d[1:, :] |= m[:-1, :]
    d[:-1, :] |= m[1:, :]
    d[:, 1:] |= m[:, :-1]
    d[:, :-1] |= m[:, 1:]
    return d


def remove_white_bg(img, thresh=238):
    """从边缘 flood-fill 去除近白色背景，保留主体内部的白色。"""
    img = img.convert("RGBA")
    arr = np.array(img)
    rgb = arr[:, :, :3]
    near_white = (rgb[:, :, 0] >= thresh) & (rgb[:, :, 1] >= thresh) & (rgb[:, :, 2] >= thresh)
    h, w = near_white.shape
    bg = np.zeros((h, w), dtype=bool)
    bg[0, :] = near_white[0, :]
    bg[-1, :] = near_white[-1, :]
    bg[:, 0] = near_white[:, 0]
    bg[:, -1] = near_white[:, -1]
    while True:
        grown = near_white & dilate(bg)
        if (grown == bg).all():
            break
        bg = grown
    alpha = np.where(bg, 0, 255).astype(np.uint8)
    arr[:, :, 3] = alpha
    out = Image.fromarray(arr, "RGBA")
    # 边缘轻微羽化
    a = out.getchannel("A").filter(ImageFilter.GaussianBlur(1.5))
    out.putalpha(a)
    return out


def content_bbox(img, alpha_thresh=8):
    a = np.array(img.getchannel("A"))
    ys, xs = np.where(a > alpha_thresh)
    if len(xs) == 0:
        return (0, 0, img.width, img.height)
    return (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def main():
    mascot_raw = Image.open(MASCOT_SRC)
    icon_raw = Image.open(ICON_SRC).convert("RGBA")
    print("mascot:", mascot_raw.size, "icon:", icon_raw.size)

    # ---------- 1. 吉祥物抠图 ----------
    mascot = remove_white_bg(mascot_raw)
    bbox = content_bbox(mascot)
    mascot = mascot.crop(bbox)
    print("mascot trimmed:", mascot.size)

    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)

    # 应用内展示用（512px 宽）
    scale = 512.0 / mascot.width
    mascot_512 = mascot.resize((512, int(mascot.height * scale)), Image.LANCZOS)
    mascot_512.save(os.path.join(nodpi, "img_doudou.png"))

    # ---------- 2. 圆形头像（白底圆 + 细边） ----------
    AV = 192
    avatar = Image.new("RGBA", (AV, AV), (0, 0, 0, 0))
    d = ImageDraw.Draw(avatar)
    d.ellipse([0, 0, AV - 1, AV - 1], fill=(255, 255, 255, 255),
              outline=(232, 234, 240, 255), width=3)
    inner = int(AV * 0.86)
    m = mascot.copy()
    s = inner / m.width
    m = m.resize((inner, int(m.height * s)), Image.LANCZOS)
    avatar.alpha_composite(m, ((AV - m.width) // 2, (AV - m.height) // 2 + 6))
    # 裁回圆形
    final_avatar = Image.new("RGBA", (AV, AV), (0, 0, 0, 0))
    final_avatar.paste(avatar, (0, 0), circle_mask(AV))
    final_avatar.save(os.path.join(nodpi, "img_doudou_circle.png"))

    # ---------- 3. 图标：原图已是完整设计，只做缩放，不裁剪 ----------
    icon_sq = icon_raw  # 原图即正方形全幅设计（圆角+白角均为设计一部分）
    print("icon source:", icon_sq.size)

    # 采样渐变色（左上 / 右下），供 background 使用
    w, h = icon_sq.size
    c1 = icon_sq.getpixel((int(w * 0.08), int(h * 0.08)))
    c2 = icon_sq.getpixel((int(w * 0.92), int(h * 0.92)))
    print("gradient colors: #%02X%02X%02X -> #%02X%02X%02X" % (c1[0], c1[1], c1[2], c2[0], c2[1], c2[2]))

    # ---------- 4. 各密度图标 ----------
    for density, (legacy_px, fg_px) in DENSITIES.items():
        mdir = os.path.join(RES, "mipmap-" + density)
        os.makedirs(mdir, exist_ok=True)

        # legacy / round：原图纯缩放，零裁剪零蒙版
        icon_resized = icon_sq.resize((legacy_px, legacy_px), Image.LANCZOS)
        icon_resized.save(os.path.join(mdir, "ic_launcher.png"))
        icon_resized.save(os.path.join(mdir, "ic_launcher_round.png"))

        # adaptive foreground：整图缩到画布 62%（66dp 安全区内），
        # 保证任何启动器蒙版都裁不到图内任何元素
        fg = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
        inner_px = int(fg_px * 0.62)
        icon_inner = icon_sq.resize((inner_px, inner_px), Image.LANCZOS)
        fg.alpha_composite(icon_inner, ((fg_px - inner_px) // 2, (fg_px - inner_px) // 2))
        fg.save(os.path.join(mdir, "ic_launcher_foreground.png"))

    print("done.")


if __name__ == "__main__":
    main()
