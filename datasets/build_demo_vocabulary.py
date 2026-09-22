#!/usr/bin/env python3
"""Curate exact dictionary entries for a small, scenario-based sign demo."""

from __future__ import annotations

import json
import html
import base64
import shutil
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "chinese-sign-language-dictionary"
DEST = ROOT / "demo_help_vocab"

# Exact meanings in signs.db. These are candidate labels, not verified camera classes.
SCENES = {
    "01_基础沟通": "我 你 是 不是 有 没有 要 可以 不会 不知道 聋人 手语 说 听 手机 打字 写字 慢 重复 谢谢 对不起 请 一 二 三".split(),
    "02_求助安全": "帮助 救 急救 救护车 危险 丢 疼痛 伤口 医院 医生 药 报警 警察 联系 快 现在".split(),
    "03_问路": "哪里 这里 那里 去 到 怎么 走 左 右 前 后 上 下 附近 远 近 地铁 公交车 车站 出口 厕所 电梯 地图 地址".split(),
    "04_寻人": "找 人 儿童 老人 爸爸 妈妈 儿子 女儿 姓名 认识 看 穿 衣服 照片 分开".split(),
    "05_购物询价": "买 卖 商店 超市 多少 多少钱 钱 贵 便宜 微信 支付宝 银行卡 发票 收据 大 小 颜色 换 退 十".split(),
}

# A first filming/training tranche. Even these must pass chest-camera feasibility tests.
CORE = set("我 你 不是 没有 要 可以 聋人 手语 手机 打字 谢谢 请 帮助 救 急救 危险 疼痛 医院 报警 警察 哪里 这里 去 怎么 走 左 右 地铁 车站 出口 厕所 找 人 儿童 老人 姓名 照片 买 商店 超市 多少 多少钱 贵 便宜 微信 支付宝 大 小".split())

# Only exact lexical matches are counted as covered. These are intent-level cues;
# they do not prescribe grammatical sign order or imply that missing words are signed.
INTENTS = [
    ("基础", "我是聋人，请用文字交流", "我 聋人 请 打字 手机", "需要现场确认对方是否愿意打字；不能把‘听’推断为‘听不见’。"),
    ("基础", "我需要帮助", "我 要 帮助", "核心求助句。"),
    ("基础", "请重复/慢一点", "请 重复 慢", "适合屏幕文字交流；口语慢说对部分使用者未必有帮助。"),
    ("安全", "这里有危险，请帮忙", "这里 危险 帮助", "危险类型需要进一步指认或文字确认。"),
    ("安全", "请叫救护车", "请 救护车 急救", "‘叫’无独立词条；必须让人确认是否真的需要呼叫。"),
    ("安全", "请帮我报警", "请 帮助 报警 警察", "确认地点与事件后再拨打。"),
    ("安全", "我受伤/疼痛，需要医生", "我 疼痛 伤口 医生", "‘受伤’无精确词条，伤口不能涵盖所有伤情。"),
    ("安全", "医院在哪里", "医院 哪里", "医院手势涉及头部，胸前视角需实测。"),
    ("问路", "我迷路了，车站在哪里", "我 找 车站 哪里", "‘迷路’无精确词条；找+车站只是线索。"),
    ("问路", "地铁站/公交车站在哪里", "地铁 公交车 车站 哪里", "区分地铁和公交；不要同时生成两者。"),
    ("问路", "厕所/出口在哪里", "厕所 出口 哪里", "两个独立意图，依识别到的名词输出。"),
    ("问路", "我怎么去那里", "我 怎么 去 那里", "场景照片中的指向目标要与手势时间对齐。"),
    ("问路", "请在地图上指给我", "请 地图", "‘指给我’未覆盖，需要补采或让用户指触屏幕。"),
    ("寻人", "请帮我找孩子/老人", "请 帮助 找 儿童 老人", "儿童与老人是可替换槽位；寻人应优先确认身份与安全。"),
    ("寻人", "我和家人走散了", "我 分开 找", "‘家人/走散’无精确词条，不能仅凭‘分开’定论。"),
    ("寻人", "你认识照片上的人吗", "你 认识 照片 人", "照片可能涉及他人隐私，默认只在本机展示。"),
    ("寻人", "这个人叫什么名字", "人 姓名", "‘这个’需要指向/画面目标，当前词库未单独覆盖。"),
    ("购物", "这个多少钱", "多少钱", "‘这个’靠指向商品与照片确定，若目标不明确应追问。"),
    ("购物", "我想买这个", "我 要 买", "商品目标需要指向或点击照片确认。"),
    ("购物", "可以便宜一点吗", "可以 便宜", "‘一点’未覆盖，建议只生成‘可以便宜吗’。"),
    ("购物", "可以用微信/支付宝支付吗", "可以 微信 支付宝", "支付动作/二维码未覆盖，付款方式需明确辨别。"),
    ("购物", "可以换货/退货吗", "可以 换 退", "‘换/退’是泛义词，具体售后意图必须确认。"),
    ("购物", "请给我发票/收据", "请 发票 收据", "发票与收据不可混同，按识别结果选择。"),
    ("购物", "还有其他颜色/大小吗", "有 颜色 大 小", "‘其他’无词条；需要商品目标和尺寸上下文。"),
]

HEAD_CUES = ("头", "额", "脸", "面部", "眼", "眉", "鼻", "口", "嘴", "唇", "耳", "下巴", "太阳穴", "颊")


def main() -> None:
    labels = [term for terms in SCENES.values() for term in terms]
    assert len(labels) == 100 and len(labels) == len(set(labels)), "Need 100 distinct labels"
    assert CORE <= set(labels)
    db = sqlite3.connect(SOURCE / "signs.db")
    db.row_factory = sqlite3.Row
    images_dir = DEST / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    records = []
    unique_signs = set()

    for scene, terms in SCENES.items():
        (images_dir / scene).mkdir(exist_ok=True)
        for term in terms:
            rows = db.execute(
                "SELECT DISTINCT s.id, s.image_path, s.description FROM meanings m "
                "JOIN signs s ON s.id=m.sign_id WHERE m.text=? ORDER BY s.id", (term,)
            ).fetchall()
            if not rows:
                raise ValueError(f"No exact dictionary entry: {term}")
            diagrams = []
            for variant, row in enumerate(rows, 1):
                source = SOURCE / row["image_path"]
                if not source.is_file():
                    raise FileNotFoundError(source)
                filename = f"{term}_v{variant}.jpg"
                relative = Path("images") / scene / filename
                shutil.copy2(source, DEST / relative)
                description = row["description"] or ""
                diagrams.append({
                    "sign_id": row["id"],
                    "file": relative.as_posix(),
                    "source_file": row["image_path"],
                    "description": description,
                    "head_or_face_cue": any(cue in description for cue in HEAD_CUES),
                })
                unique_signs.add(row["id"])
            records.append({"scene": scene, "label": term, "tier": "首批" if term in CORE else "扩展", "diagrams": diagrams})

    manifest = {
        "source": "https://github.com/WishingCat/chinese-sign-language-dictionary",
        "source_note": "Image rights and use restrictions are described in the source repository; verify before redistribution or commercial use.",
        "label_count": len(labels),
        "diagram_count": sum(len(r["diagrams"]) for r in records),
        "unique_source_sign_count": len(unique_signs),
        "records": records,
    }
    (DEST / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# 求助场景手语图解候选词库",
        "",
        f"共 **{len(labels)} 个精确词条、{manifest['diagram_count']} 张原词典图解**（含多种手势变体）；首批 {len(CORE)} 词。图片是教学线稿，**不是摄像头训练帧**。",
        "",
        "来源：[Chinese Sign Language Dictionary](https://github.com/WishingCat/chinese-sign-language-dictionary)。原仓库注明用于非商业研究、教育和无障碍相关用途，并保留原书权利；对外发布、训练或商业化前请核实授权。",
        "",
        "每个词均与本地词典 `meanings.text` 精确匹配。`头脸提示` 只按词典文字自动筛选，表示胸前相机可能漏拍，**没有标记也不等于可拍**。同一词的变体均保留，不应直接合并为同一种动作。",
        "",
        "建议先让手语使用者和教师核对词义、地区变体、动作连贯性，再在实际胸前机位录制；不要直接把线稿当作真实图像训练集。",
        "",
    ]
    for scene in SCENES:
        lines += [f"## {scene.split('_', 1)[1]}", "", "| 词条 | 批次 | 图解 | 胸前拍摄提示 |", "|---|---|---|---|"]
        for record in (r for r in records if r["scene"] == scene):
            links = "、".join(f"[变体{i}]({d['file']})" for i, d in enumerate(record["diagrams"], 1))
            cue = "含头/脸等位置线索，需重点实测" if any(d["head_or_face_cue"] for d in record["diagrams"]) else "尚未实测"
            lines.append(f"| {record['label']} | {record['tier']} | {links} | {cue} |")
        lines.append("")
    lines += ["## 识别与使用边界", "", "- 相同图解可能对应多个词义；这 100 个词条是语义标签数量，不保证有 100 个视觉可区分的手势。", "- 单字词不等于完整句；否定、疑问、空间指向和面部表情不能靠大模型凭空补出。", "- 可先把识别结果当作候选词，结合照片给出 2–3 个短句供使用者确认后再外放。", ""]
    (DEST / "词汇图文索引.md").write_text("\n".join(lines), encoding="utf-8")

    cards = []
    for scene in SCENES:
        cards.append(f"<h2>{html.escape(scene.split('_', 1)[1])}</h2><div class='grid'>")
        for record in (r for r in records if r["scene"] == scene):
            badge = "首批" if record["tier"] == "首批" else "扩展"
            images = "".join(
                f"<a href='{html.escape(d['file'])}' target='_blank'><img loading='lazy' src='{html.escape(d['file'])}' alt='{html.escape(record['label'])} 变体{i}'></a>"
                for i, d in enumerate(record["diagrams"], 1)
            )
            descriptions = " ".join(d["description"] for d in record["diagrams"])
            risk = " · 头脸位置需重点实测" if any(d["head_or_face_cue"] for d in record["diagrams"]) else ""
            cards.append(
                f"<article><h3>{html.escape(record['label'])} <small>{badge}{risk}</small></h3>"
                f"{images}<details><summary>词典动作说明</summary><p>{html.escape(descriptions)}</p></details></article>"
            )
        cards.append("</div>")
    page = """<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>求助场景手语图解候选词库</title><style>
body{font-family:system-ui,-apple-system,sans-serif;max-width:1400px;margin:auto;padding:24px;color:#17212d;background:#f6f7f9}
h1{margin-bottom:4px}h2{margin-top:42px;border-bottom:1px solid #ccd3da;padding-bottom:8px}
.note{line-height:1.6;color:#475569}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px}
article{background:white;border:1px solid #dce1e6;border-radius:10px;padding:12px;min-width:0}
h3{margin:0 0 10px}small{display:block;font-size:12px;font-weight:normal;color:#64748b}
img{width:100%;height:180px;object-fit:contain;background:#fff;margin-bottom:6px}
details{font-size:13px;line-height:1.5;color:#374151}summary{cursor:pointer}
</style><h1>求助场景手语图解候选词库</h1>
<p class='note'>100 个词条、116 张词典图解，按场景分类。点击图片看原图，展开卡片看动作说明。线稿仅用于选词和教学参考，不是胸前相机训练帧。来源及使用限制见词汇图文索引。</p>
""" + "\n".join(cards) + "</html>\n"
    (DEST / "图解画廊.html").write_text(page, encoding="utf-8")
    standalone = page
    for record in records:
        for diagram in record["diagrams"]:
            path = diagram["file"]
            encoded = base64.b64encode((DEST / path).read_bytes()).decode("ascii")
            standalone = standalone.replace(
                f"src='{html.escape(path)}'",
                f"src='data:image/jpeg;base64,{encoded}'",
            )
    (DEST / "图解画廊_飞书上传版.html").write_text(standalone, encoding="utf-8")

    intent_lines = [
        "# 求助场景语义表",
        "",
        "以下词条是识别后送入语义模型的**候选线索**，不是规范的手语语序。目标句是交互意图示例，不表示相应整句已经有训练数据。涉及缺失信息的句子应要求用户确认；紧急呼叫不能自动触发。",
        "",
        "| 场景 | 目标表达 | 已有词条线索 | 缺口／输出约束 |",
        "|---|---|---|---|",
    ]
    for scene, sentence, cues, note in INTENTS:
        for cue in cues.split():
            if cue not in labels:
                raise ValueError(f"Intent cue missing: {cue}")
        intent_lines.append(f"| {scene} | {sentence} | {cues.replace(' ', '、')} | {note} |")
    intent_lines += [
        "", "## 补拍优先词/信息", "",
        "这些表达在所选词典中未找到**精确同名**图解，或现有单字不足以表达对应意图：迷路、走失、受伤、救命、电话号码、这个/那个、指给我、现金、扫码、退货、换货、付款。需要另查权威词典并由手语使用者核对，或在 Demo 中让用户点击照片目标、选择短语按钮、打字补充。",
        "", "## 第一视角照片的使用", "",
        "照片可辅助确定‘这个商品/路口/站牌’等环境对象，但不能证明手势包含未观察到的否定、疼痛部位、身份、价格或求救严重程度。对外放句子显示识别到的词、模型补足的词及置信/不确定提示，再由用户点选确认。",
        "",
    ]
    (DEST / "求助场景语义表.md").write_text("\n".join(intent_lines), encoding="utf-8")
    print(f"Created {len(labels)} labels, {manifest['diagram_count']} diagrams, {len(unique_signs)} distinct source signs; core {len(CORE)}")


if __name__ == "__main__":
    main()
