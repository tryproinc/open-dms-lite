"""取扱説明書用の画面から、顔の映像を「白地 + 人型の絵」に置き換える。

    python tools/anonymize_screenshot.py <kind> <入力.png> <出力.png> [--status-from 基準.png]

kind:
  video   映像の上に操作パネルがある画面(前検査・較正・運行中・結果・詳細)
          映像の範囲(最上部の状態バーの下〜操作パネルの上端)を白地にし、アプリが描いた
          顔の点から頭・首・肩の絵を描く。アプリの描画(点・文字)は元の画素を戻す。
  alert   全画面の警告。映像が透けて見えるので、地色の単色に置き換え、白い文字だけを戻す。
  plain   全面を黒い板が覆う画面(閾値設定・注意事項)。状態バーだけ差し替える。

写真を加工するのではなく、点の外接矩形から絵を描くので、人物は特定できず、背景も写らない。
最上部の状態バー(時刻・電波)は、背景の映像が透けるため、`--status-from` の画面から差し替える
(黒地の画面の状態バーを使う)。

必要なもの: opencv-python、numpy。
"""
from __future__ import annotations

import argparse

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

STATUS_H = 120          # 状態バーの高さ(px。1084x2412 の画面)
NAV_H = 130             # 下端のナビゲーションバーの高さ
PANEL_BGR = np.array([28, 23, 16])   # 操作パネルの地色(#10171C)

SKIN = (190, 205, 225)
HAIR = (70, 70, 80)
SHIRT = (175, 175, 175)
LINE = (120, 120, 130)


def keypoint_mask(bgr: np.ndarray) -> np.ndarray:
    """アプリが描いた顔の点(緑・黄)の画素。"""
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    green = (h >= 45) & (h <= 85) & (s >= 110) & (v >= 120)
    yellow = (h >= 20) & (h <= 35) & (s >= 150) & (v >= 170)
    return (green | yellow).astype(np.uint8)


def panel_top(bgr: np.ndarray, start: int = 500) -> int:
    """操作パネルの上端。パネルの地色が行の大半を占める行が3行続く最初の位置。"""
    h = bgr.shape[0]
    near = (np.abs(bgr.astype(int) - PANEL_BGR).max(axis=2) <= 8)
    frac = near.mean(axis=1)
    run = 0
    for y in range(start, h):
        run = run + 1 if frac[y] >= 0.7 else 0
        if run >= 3:
            return y - 2
    return h


def draw_figure(canvas: np.ndarray, x: int, y: int, bw: int, bh: int, bottom: int) -> None:
    """点の外接矩形(x, y, bw, bh)から、頭・首・肩の絵を描く。"""
    cx, cy = x + bw // 2, y + bh // 2
    ax, ay = int(bw * 0.58), int(bh * 0.66)
    sh_y = y + bh + int(bh * 0.28)
    pts = np.array([
        [cx - int(bw * 0.28), y + bh], [cx + int(bw * 0.28), y + bh],
        [cx + int(bw * 0.35), sh_y], [cx + int(bw * 1.45), sh_y + int(bh * 0.35)],
        [cx + int(bw * 1.6), bottom], [cx - int(bw * 1.6), bottom],
        [cx - int(bw * 1.45), sh_y + int(bh * 0.35)], [cx - int(bw * 0.35), sh_y],
    ], np.int32)
    cv2.fillPoly(canvas, [pts], SHIRT, cv2.LINE_AA)
    cv2.polylines(canvas, [pts], True, LINE, 3, cv2.LINE_AA)
    cv2.rectangle(canvas, (cx - int(bw * 0.22), cy), (cx + int(bw * 0.22), sh_y), SKIN, -1)
    cv2.ellipse(canvas, (cx, cy - int(bh * 0.08)), (ax + int(bw * 0.06), ay + int(bh * 0.06)), 0, 180, 360, HAIR, -1, cv2.LINE_AA)
    cv2.ellipse(canvas, (cx, cy), (ax, ay), 0, 0, 360, SKIN, -1, cv2.LINE_AA)
    cv2.ellipse(canvas, (cx, cy), (ax, ay), 0, 0, 360, LINE, 3, cv2.LINE_AA)
    for sx in (-1, 1):
        cv2.ellipse(canvas, (cx + sx * ax, cy), (int(bw * 0.07), int(bh * 0.12)), 0, 0, 360, SKIN, -1, cv2.LINE_AA)


def face_box(bgr: np.ndarray, top: int, bottom: int):
    """顔の点の外接矩形。点が少なければ None。背景の緑を拾わないよう、分布の上下2%を除く。"""
    kp = keypoint_mask(bgr)
    kp[:top] = 0
    kp[bottom:] = 0
    pts = cv2.findNonZero(kp)
    if pts is None or len(pts) < 200:
        return None
    xy = pts.reshape(-1, 2)
    x0, y0 = np.percentile(xy, 2, axis=0).astype(int)
    x1, y1 = np.percentile(xy, 98, axis=0).astype(int)
    return int(x0), int(y0), int(x1 - x0), int(y1 - y0)


def replace_status_bar(out: np.ndarray, status_from: np.ndarray | None) -> None:
    """最上部の状態バーを、黒地の画面のものに差し替える(背景の映像が透けるため)。"""
    if status_from is not None:
        out[:STATUS_H] = status_from[:STATUS_H]
    else:
        out[:STATUS_H] = 30


def text_mask(bgr: np.ndarray, lo: int = 190) -> np.ndarray:
    return (bgr.min(axis=2) >= lo)[..., None]


def draw_title(out: np.ndarray, text: str, width: int, center_x: int = 542, y: int = 147, height: int = 42) -> None:
    """版表示を描き直す。映像が明るくて元の文字を取り出せないとき用(幅を実測に合わせる)。"""
    out[y - 6:y + height + 6, 100:985] = 255
    font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 40)
    size = 40
    while font.getlength(text) > width and size > 20:
        size -= 1
        font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", size)
    img = Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(img)
    w = font.getlength(text)
    d.text((center_x - w / 2, y + 2), text, font=font, fill=(200, 110, 0))
    out[:] = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)


def process_video(img: np.ndarray, status_from, title: str | None = None, title_width: int = 561) -> np.ndarray:
    h = img.shape[0]
    bottom = panel_top(img)
    canvas = np.full_like(img, 255)
    box = face_box(img, STATUS_H, bottom)
    if box is not None:
        draw_figure(canvas, *box, bottom)
    out = img.copy()
    out[STATUS_H:bottom] = canvas[STATUS_H:bottom]
    # 点は元の画素を戻す
    keep = cv2.dilate(keypoint_mask(img), np.ones((3, 3), np.uint8))
    keep[:STATUS_H] = 0
    keep[bottom:] = 0
    out = np.where(keep[..., None].astype(bool), img, out)
    # 版表示(橙の文字)は、白地で読めるよう濃い橙にして戻す
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    orange = ((hsv[..., 0] >= 8) & (hsv[..., 0] <= 28) & (hsv[..., 1] >= 90) & (hsv[..., 2] >= 150))
    orange[:130] = False
    orange[205:] = False
    out[orange] = (0, 110, 200)
    # 診断の文字(顔検出:OK …)の箱: 暗い板を敷き、白い文字だけを戻す
    plate = (slice(215, 290), slice(100, 985))
    reg = img[plate]
    txt = text_mask(reg)
    if txt.any():
        ys, xs = np.where(txt[..., 0])
        y0, y1, x0, x1 = ys.min() - 14, ys.max() + 14, xs.min() - 18, xs.max() + 18
        sub = out[plate]
        sub[max(y0, 0):y1, max(x0, 0):x1] = 35
        out[plate] = np.where(txt, reg, sub)
    if title:
        draw_title(out, title, title_width)
    replace_status_bar(out, status_from)
    return out


def process_alert(img: np.ndarray, status_from) -> np.ndarray:
    h = img.shape[0]
    body = slice(STATUS_H, h - NAV_H)
    tint = np.median(img[body].reshape(-1, 3), axis=0)
    out = img.copy()
    out[body] = tint.astype(np.uint8)
    txt = text_mask(img[body], 215)
    out[body] = np.where(txt, img[body], out[body])
    replace_status_bar(out, status_from)
    return out


def process_plain(img: np.ndarray, status_from) -> np.ndarray:
    """全面を黒い板が覆う画面(閾値設定・注意事項)。映像は5%しか透けないので、状態バーだけ差し替える。"""
    out = img.copy()
    replace_status_bar(out, status_from)
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["video", "alert", "plain"])
    parser.add_argument("src")
    parser.add_argument("dst")
    parser.add_argument("--status-from")
    parser.add_argument("--title", help="版表示を描き直す文字列(元の文字が読めないとき)")
    parser.add_argument("--title-width", type=int, default=561, help="版表示の幅(px)")
    args = parser.parse_args()
    img = cv2.imread(args.src)
    status = cv2.imread(args.status_from) if args.status_from else None
    if args.kind == "video":
        out = process_video(img, status, args.title, args.title_width)
    else:
        out = {"alert": process_alert, "plain": process_plain}[args.kind](img, status)
    cv2.imwrite(args.dst, out)


if __name__ == "__main__":
    main()
