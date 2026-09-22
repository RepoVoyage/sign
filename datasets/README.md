# 手语数据集（2026-09-21 核查）

## 已下载

### `chinese-sign-language-dictionary/`

- 来源：https://github.com/WishingCat/chinese-sign-language-dictionary
- 内容：`signs.db`、`sign_themed.db` 和 `images/`。本地核对为 6,699 条手势图、8,687 条中文释义。
- 用法：`sqlite3 chinese-sign-language-dictionary/signs.db "SELECT m.text, s.image_path, s.description FROM meanings m JOIN signs s ON s.id=m.sign_id WHERE m.text='帮助';"`
- 性质：词典图解，不是相机拍摄的真人训练照片。可用于选词、学习动作和建立标注表，不能直接代表 GO 3S 胸前视角。
- 权利：仓库作者声明仅供非商业学习、研究和无障碍技术开发；图片为《国家通用手语词典》的衍生内容，原出版方及编制单位保有权利。请勿将其当作可任意再分发或商用的数据。

### `csl-clinic-sample/`

- 来源：https://huggingface.co/datasets/rzhao/CSL-Clinic
- 内容：测试集原始标注表 `metadata-full.csv`（500 行）及两段 MP4 样本；`sample.csv` 列出这两段视频的对应标注。
- 本地校验：两段视频均可解码，约 30 fps、1920×1080；SHA-256 与 Hugging Face 文件清单一致，见 `SHA256SUMS`。
- 性质：真人正面拍摄的中文手语句子，包含手语词序列和中文句子。属于医疗场景，不能直接代表胸前第一视角。
- 权利：数据页未声明明确的许可证；当前仅保存少量样本供内部研究和可行性检查。完整数据集约 28 GB。公开传播或产品使用前需向发布者核实数据权利。

## 暂未下载的中国手语视频集

- [中科大 SLR500 / CSL](https://ustc-slr.github.io/datasets/2015_csl/)：孤立词和连续句子，含 RGB、深度、骨骼数据；官方要求签署协议申请研究用途访问。
- [中科大 CSL-Daily](https://ustc-slr.github.io/datasets/2021_csl_daily/)：日常场景连续手语，含词级标注和中文译文；官方要求签署协议申请。
- [NationalCSL-DP](https://lise.lsnu.edu.cn/kxyj/dbxcg2.htm)：大词汇量中国手语视频及帧；发布页既提到 CC BY 4.0，也要求高校／研究机构提交访问协议。按申请流程获取后再下载。

## 给本项目的使用建议

图解数据适合挑选“帮助、厕所、多少钱”等原型词汇；真人视频适合检查时序输入、标注和模型流程。训练部署到 GO 3S 胸前相机前，仍需采集该视角的真实手语视频，并保留“完整原意”与“相机实际可见信息”两套标注。

## 求助场景 Demo 词库

- [图解画廊](demo_help_vocab/图解画廊.html)：100 个词条、116 张图解，按基础沟通、求助安全、问路、寻人、购物询价分类。
- [飞书上传版画廊](demo_help_vocab/图解画廊_飞书上传版.html)：图片嵌入 HTML 本身，上传单个文件即可预览（仍需以飞书实际预览效果为准）。
- [词汇图文索引](demo_help_vocab/词汇图文索引.md)：精确词条、变体图片链接、首批/扩展批次和胸前视角提示。
- [求助场景语义表](demo_help_vocab/求助场景语义表.md)：24 个交互意图及现有词条线索、缺口。
- [结构化清单](demo_help_vocab/manifest.json)：图片路径、来源路径、原词典动作说明；运行 `python3 datasets/build_demo_vocabulary.py` 可重新生成。
