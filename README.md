# PDF 全能转换箱 (PdfToolApp)

一款**纯本地离线**运行的安卓工具箱：PDF 互转 + 图片工具，共 17 种功能。
适配 Android 6.0（API 23）及以上机型。所有处理都在手机本地完成，
**文件不会上传到任何服务器**。

> 📥 **直接下载安装包**：[`releases/PdfToolApp-v1.1.0.apk`](releases/PdfToolApp-v1.1.0.apk)
> SHA-256：`180ce3e085b18d916c1b35de4812b9a83fcc976b58ef8aa5adebcb969cd5fafb`

---

## ✨ 功能清单

### PDF 转其他
| 功能 | 说明 | 输出 |
| --- | --- | --- |
| PDF → 单图 | 每一页导出为一张 PNG（打包 ZIP） | `.zip` |
| PDF → 长图 | 把所有页面拼成一张长图 | `.png` |
| PDF → TXT | 提取 PDF 文字内容 | `.txt` |
| PDF → Word | 提取文字生成 `.docx` | `.docx` |
| PDF → Excel | 按行提取文字生成 `.xlsx` | `.xlsx` |
| PDF → PPT | 每页作为一张幻灯片（图片） | `.pptx` |

### 其他转 PDF
| 功能 | 说明 | 输出 |
| --- | --- | --- |
| TXT → PDF | 文本排版后生成 PDF | `.pdf` |
| 图片 → PDF | 一张或多张图片各占一页 | `.pdf` |
| 长图 → PDF | 一张长图作为一页 | `.pdf` |
| Word → PDF | `.docx` 文字转 PDF | `.pdf` |
| Excel → PDF | `.xlsx` 内容转 PDF | `.pdf` |
| PPT → PDF | `.pptx` 文字转 PDF | `.pdf` |

### PDF 工具
| 功能 | 说明 | 输出 |
| --- | --- | --- |
| 合并 | 多个 PDF 按顺序合并 | `.pdf` |
| 拆分 | 每页拆成独立 PDF（打包 ZIP） | `.zip` |
| 压缩 | 降低分辨率显著减小体积 | `.pdf` |

### 图片工具
| 功能 | 说明 | 输出 |
| --- | --- | --- |
| 图片 → 透明图标 | 自动抠除四角背景色，生成 **16/24/32/48/64/128/256** 七档尺寸的多分辨率图标 | `.ico` |
| 图片压缩 | 按压缩等级批量压缩（高画质/标准/强压缩），打包 ZIP | `.zip` |

> 🛠️ **v1.1.0 修复**：「图片 → 透明图标」的输出改由 App 直接创建，
> **保存的文件必定为 `.ico` 类型**（此前部分机型的系统"另存为"会把它强制写成 `.jpg`）。
> Android 10+ 存入 `Pictures/PdfTool/`，Android 6–9 存在应用目录并支持一键分享导出。
> 详见 [CHANGELOG.md](CHANGELOG.md)。

---

## 🧱 技术栈与许可证

| 用途 | 方案 | 许可证 |
| --- | --- | --- |
| PDF 读写 / 渲染 / 合并 / 拆分 / 压缩 | [Apache PDFBox for Android](https://github.com/TomRoush/PdfBox-Android) | Apache-2.0 |
| Word/Excel/PPT 的读与写 | 手写最小 OOXML（zip + XML） | 项目自带 |
| ICO 图标容器 | 自实现 ICONDIR/PNG 多尺寸封装 | 项目自带 |

> ⚠️ **为什么不用 Apache POI？** POI 依赖 `java.awt`，而 Android 平台没有 `java.awt`。
> 因此 Office 文档读写改为手写最小可用 OOXML，规避兼容性问题的同时去掉了数十 MB 的沉重依赖。

---

## 🔒 隐私与离线

- 应用使用 **Storage Access Framework** 与 **MediaStore** 读写文件，不申请存储权限。
- 所有转换在本地完成：无网络请求、无广告、无埋点。

---

## 🏗️ 构建步骤

### 方式一：Android Studio（推荐）
1. 用 Android Studio（Hedgehog/Iguana 或更新）打开项目根目录。
2. 首次打开若提示缺少 Gradle Wrapper，执行一次 `gradle wrapper` 或让 Studio 自动下载 Gradle 8.2。
3. 直接 Run 或 Build APK（SDK Platform 34 + Build-Tools 34.0.0 + JDK 17）。

### 方式二：离线手工脚本（本项目发布包的实际构建方式）
[`scripts/build_apk.sh`](scripts/build_apk.sh) 演示了不依赖 Gradle 的完整手工流程：
aapt2 编译资源 → aapt2 link → javac/kotlinc 编译 → d8 dex → 组装 → zipalign → apksigner 签名。
脚本内的 SDK/JDK/依赖路径为构建机特定配置，复用时请按本机路径调整。

---

## 📂 目录结构

```
PdfToolApp/
├── app/src/main/           # 全部源码（Kotlin + 资源）
│   └── java/com/pdftool/app/
│       ├── engine/         # 转换引擎（Converters/IO/OfficeKit/ShareProvider…）
│       ├── ui/             # Activity 与适配器
│       └── model/          # 功能枚举与元数据
├── releases/               # 已签名的发布 APK
├── scripts/                # 离线构建脚本
└── CHANGELOG.md            # 版本变更记录
```

---

## 📄 许可证说明

第三方依赖均为 Apache-2.0，可自由用于闭源/商业产品；项目本体许可证由仓库所有者确定。
