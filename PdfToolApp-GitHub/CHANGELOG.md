# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。

## [1.1.0] - 2026-09-30

### 修复
- **图片 → 透明图标：输出文件类型不再被改成 `.jpg`**。
  此前输出走系统「另存为」（ACTION_CREATE_DOCUMENT），部分厂商文件选择器不识别
  `image/x-icon`，会把文件强制创建为 `.jpg`，导致转换结果不是图标格式。
  现改为 App 自行创建输出文档，文件名与类型在代码中固定为 `.ico`：
  - Android 10+：MediaStore 写入 `Pictures/PdfTool/<原图名>_时间戳.ico`，
    写入完成前保持 `IS_PENDING`，完成后发布；媒体库拒绝时回落到 Downloads 集合；
  - Android 6–9：写入应用专属 `output/` 目录，经新增的 `ShareProvider`
    以 `content://` 提供给分享目标；
  - 转换失败自动清理半成品文件。

### 变更
- 分享意图补充 `ClipData` 授权，兼容更多分享目标（Android 10+ 必需）。
- `build_apk.sh` 改用固化的签名密钥（`_dl/pdftool-key.jks`），保证版本间签名一致、可覆盖安装。

### 升级说明
- versionCode 1 → 2；与 1.0.0 签名证书一致（SHA-256 `dfe92300…4dbc9c`），可直接覆盖安装。

## [1.0.0] - 2026-09-20

### 新增
- 首个公开版本：PDF 互转 12 种方向、合并/拆分/压缩 3 种工具、图片压缩。
- 「图片 → 透明图标」：四角取色抠底 + 多尺寸 ICO 容器（16–256px 七档 PNG）。
