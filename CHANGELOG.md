# 更新日志

## v1.1.3-fixed.2 (2026-10-22)

本分支基于上游 [yfishyon/doumcp](https://github.com/yfishyon/doumcp) v1.1.3（commit 0c84d18）。

### 修复

- **引用回复必崩（核心修复）**：`sendTextMessage` 带 `quoteMessageId` 时，上游在反射构造
  宿主 `com.bytedance.im.core.proto.ReferenceInfo`（Wire 生成类）时漏设 required 字段
  `referenced_message_status`，`build()` 抛
  `IllegalStateException: Required field not set: referenced_message_status`，引用回复完全不可用。
  现按 setter 真实参数类型（宿主 `MessageStatus` 消息类）经其无参 Builder 构造默认实例传入
  （`ImMsgBridge.kt:442` 起，含 static final 自引用字段兜底）。真机验证通过：
  引用消息正常发出，对端可见引用块。
- **sendTextMessage 缺少参数 schema**：上游该工具 inputSchema 为空 `{"type":"object"}`，
  部分 MCP 客户端会把 `quoteMessageId` 压成字符串或丢参。现补完整 schema：
  `conversationId` / `text` 必填 string，`quoteMessageId` 宽松 anyProp，`quoteNickname` 可选 string
  （`ImTools.kt:44`）。

### 已知未修复

- `revokeMessage` 在部分场景返回「IM 返回失败」（上游原有问题，未改动）。

### 构建

- Gradle wrapper 与依赖仓库换为腾讯/阿里镜像，国内网络可直接构建；wrapper 网络超时放宽至 60s。
- 版本号：`v1.1.3` → `v1.1.3-fixed.1`。
