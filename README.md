# Douyin-mcp-fixed

> 本仓库是 [yfishyon/doumcp](https://github.com/yfishyon/doumcp)（v1.1.3）的修复分支，项目原名「抖M / DouMCP」。修复内容见下方 [本分支修复了什么](#-本分支修复了什么) 与 [CHANGELOG.md](CHANGELOG.md)。

## 🩹 本分支修复了什么

### 1. 引用回复必崩（核心修复）

- **症状**：`sendTextMessage` 只要带 `quoteMessageId` 就报错
  `IllegalStateException: Required field not set: referenced_message_status`，引用回复完全不可用。
- **根因**：模块用反射构造宿主的 `com.bytedance.im.core.proto.ReferenceInfo`（Wire 生成类），
  只设置了 `referenced_message_id` / `hint` / `ref_message_type` 三个字段，
  漏掉了 Wire 的 required 字段 `referenced_message_status`（类型是宿主的 `MessageStatus` 消息类，不是 Int），
  `build()` 时直接抛异常。原注释声称「拷贝已有引用模板」，实际代码是从零构造 Builder，字段就这么丢了。
- **修法**（`ImMsgBridge.kt:442` 起）：反射取 `referenced_message_status` setter，
  按其真实参数类型（宿主 MessageStatus）经无参 Builder + `build()` 构造默认实例传入；
  Builder 不可用时兜底取该类 static final 自引用字段。已在真机验证：引用消息可正常发出并被对端看到引用块。
- 附带说明：`revokeMessage` 撤回在部分场景仍返回「IM 返回失败」（上游原有问题，本分支未改动）。

### 2. sendTextMessage 缺少参数 schema

- **症状**：该工具的 inputSchema 是空的 `{"type":"object"}`，参数只在中文描述里，
  部分 MCP 客户端会把 `quoteMessageId` 压成字符串甚至丢参数。
- **修法**（`ImTools.kt:44`）：补上完整 schema——`conversationId`/`text` 为必填 string，
  `quoteMessageId` 用宽松 anyProp（string / number 都能收），`quoteNickname` 为可选 string。

### 3. 构建镜像配置

- `gradle-wrapper.properties` / `settings.gradle.kts` 换成腾讯/阿里镜像（国内网络可直出包），
  并把 wrapper 网络超时放宽到 60s。源码构建不再需要代理。

---

以下为上游原 README（项目名保留「抖M/DouMCP」，即本项目）：

# 抖M（DouMCP）

抖音 MCP Xposed 模块 —— 注入抖音进程内启动 MCP 服务，让 Claude 等 AI 客户端**直接操控一台真实登录的抖音**。

与走网页解析 / 协议模拟的抖音 MCP 不同：抖M 活在抖音自己的进程里，所有操作复用宿主的接口与 IM SDK —— 登录态、设备签名、加密全部现成，稳定跟随抖音版本运行。

📦 LSPosed 模块仓库：[Xposed-Modules-Repo/io.github.yfishyon.doumcp](https://github.com/Xposed-Modules-Repo/io.github.yfishyon.doumcp) —— 可在 LSPosed 管理器里直接搜到并下载

## ✨ 功能特性

- **账号** —— 多账号列表 / 一键切换 / 任意用户资料（uid 或 secUid）
- **私信 IM** —— 会话列表（火花/小火人）、消息分页（全类型解析）、发文本（含引用回复）、发图（同步返回 msgId）、撤回、编辑、表情表态、输入状态、已读、未读统计、云表情目录
- **视频** —— 作品详情（视频/图文）、用户作品列表（分页）
- **评论** —— 一级评论 + 楼中楼（表情/图片/@提及）、发表评论（顶评/回复/楼中楼，文字 / 表情包 / 图片）、批量删除评论
- **发布** —— 发布图文图集 / 图集内动图（实况）/ 普通视频；本地文件直传（模块走宿主上传链路）、正文 #话题 与 @提及、配乐搜索、24 小时动态；批量删除自己的作品
- **搜索** —— 综合搜索（聚合或按类型过滤，分页）
- **本地数据库** —— 直接 SQL 查询抖音全部 SQLite 库；**加密库自动解密**（含 IM 消息库，任意账号）
- **进程内服务** —— MCP 服务跑在抖音进程内，回环地址 + Bearer 密钥鉴权
- **逆向调试（仅 debug 包）** —— 搜类/搜方法/搜字段（DexKit 结构查询）、运行时反射读写与调用、动态 hook 观察与篡改、线程/Activity 堆栈、堆快照等；release 包**不含**这组工具

## 🔧 工作原理

```
Claude / MCP 客户端
      │  HTTP (Streamable) + Bearer Token
      ▼
127.0.0.1:19320  ←── Ktor 服务（抖音进程内）
      │
      ├── 反射调用抖音自己的接口与 SDK（发消息/评论/搜索…）
      ├── DexKit 定位混淆代码（全等字符串锚点，结果两级缓存）
      └── 宿主 WCDB 直解加密数据库
```

## 📦 安装

1. 需要 root + [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk 版）
2. 安装抖M APK，在 LSPosed 中启用并勾选作用域：**抖音**
3. 强制停止抖音并重新打开，出现「抖M：服务已启动」提示即生效
4. 配置入口：**抖音设置页最上方「抖M设置」**（原生样式）—— 端口（默认 19320）与鉴权密钥，保存后重启抖音生效

### MCP 客户端配置

支持 Streamable HTTP 传输的客户端（Claude Code、Claude Desktop 等）：

```json
{
  "mcpServers": {
    "doumcp": {
      "type": "http",
      "url": "http://<手机IP>:19320/mcp",
      "headers": {
        "Authorization": "Bearer <你的密钥>"
      }
    }
  }
}
```

> 手机与客户端需在同一网络；未配置密钥时服务不鉴权（仅建议本机调试使用）。

## 🧰 可用工具（32 个）

| 域 | 工具 | 功能 |
|----|------|------|
| 账号 | `getUserAccounts` / `switchAccount` | 已登录账号列表 / 切换账号 |
| 账号 | `getUserProfile` | 任意用户完整资料（uid/secUid） |
| 视频 | `getAwemeDetail` | 作品详情（视频/图文，含统计与地址） |
| 视频 | `getUserAwemes` | 用户作品列表（分页） |
| 搜索 | `search` / `searchUsers` | 综合搜索（聚合或 type 过滤）/ 账号搜索 |
| 评论 | `getComments` / `getCommentReplies` | 一级评论 / 楼中楼（含表情、图片、@提及） |
| 评论 | `getCommentStickers` | 评论表情包（动图）推荐清单 |
| 评论 | `getStickerSets` | 我添加的表情集 / 表情集里的表情（带文字描述） |
| 评论 | `postComment` | 发表评论（顶评/回复/楼中楼/@提及，云表情 `[表情名]` 语法、表情包、本地图片） |
| 评论 | `deleteComment` | 批量删除自己发的评论（单条失败不影响其余） |
| 发布 | `publishNote` | 发布图文：图片发为图集，视频文件发为图集里的动图/实况；正文 `#话题`/`@提及`、配乐、24 小时动态 |
| 发布 | `publishVideo` | 发布普通视频作品（模块走宿主上传链路 + 完整视频参数） |
| 发布 | `searchMusic` | 搜配乐拿音乐 ID（传给上面两个发布工具的 musicId） |
| 发布 | `deleteAweme` | 批量删除自己发布的作品（直接删除，不进「最近删除」） |
| IM | `getConversations` | 会话列表（未读/最后消息/火花状态） |
| IM | `getMessages` | 消息分页（文本/图片/卡片全类型，含引用关系） |
| IM | `sendTextMessage` | 发文本（支持 quoteMessageId 引用回复） |
| IM | `sendImageMessage` | 发本地图片（同步等待，返回 msgId） |
| IM | `revokeMessage` / `editMessage` | 撤回 / 编辑自己发的消息 |
| IM | `setMessageReaction` | 消息表情表态（多值，add/unset） |
| IM | `sendTypingStatus` | 输入中状态（可定时自动停止） |
| IM | `markConversationRead` / `getUnreadCount` | 已读 / 全局未读统计 |
| IM | `getEmojis` | 云表情目录（可搜、含限时活动表情） |
| 系统 | `getCurrentTime` | 手机当前时间（iso/ms/秒/自定义格式） |
| 数据库 | `listDatabases` / `listTables` | 枚举库 / 表结构 |
| 数据库 | `queryDatabase` | 只读 SQL（扁平 KV 输出） |
| 数据库 | `executeDatabaseStatement` | 任意 SQL 写操作（后果自负） |
| 调试 | `ping` | 连通性检查 |

### 逆向调试工具（33 个，仅 debug 包）

> 这组工具**只在 debug 包注册**；release 包编译时会被 R8 整体裁掉（不入包、不暴露）。用途是把抖M当成一个动态逆向工作台：搜代码结构、读写运行时对象、挂 hook 观察/篡改、看调用链。

| 分组 | 工具 | 功能 |
|------|------|------|
| 静态定位 | `dexKitInfo` / `dexKitInitCache` | 桥状态 / 初始化全量缓存 |
| 静态定位 | `findClasses` / `findMethods` / `findFields` | 按结构（类型/修饰符/原始 DEX 标志/注解/数量/继承/字段读写/调用关系）或字符串锚点搜类/方法/字段 |
| 静态定位 | `batchFindUsingStrings` | 多组字符串锚点批量检索（组内 AND） |
| 静态定位 | `dumpClass` / `dumpMethod` / `dumpField` | 类结构 / 方法细节（含 invokes、callers、smali 助记符、字段读写方）；支持按描述符定位 |
| 静态定位 | `findCallers` / `findStringUsage` | 静态调用者 / 字符串用处 |
| 运行时 | `runtimeInfo` / `packageInfo` / `readPreferences` | 进程与框架信息 / 包信息 / 宿主 SP |
| 运行时 | `reflectClass` / `loadClass` / `listClassLoaders` / `listEnumConstants` / `dumpStatics` | 真实反射结构 / 类加载 / 枚举 / 静态字段（找单例） |
| 运行时 | `inspectObject` / `readField` / `writeField` | 对象路径解析与字段读写（含 final 尝试） |
| 运行时 | `newInstance` / `invokeMethod` / `dynamicProxy` | 构造对象（返回句柄）/ 反射调用（可走原始实现或完整 hook 链）/ 动态代理 |
| 运行时 | `dumpStack` / `dumpActivities` / `dumpHeap` | 线程堆栈（找 caller）/ 页面栈 / 堆快照 |
| 动态 hook | `watchMethod` / `unwatchMethod` / `listWatches` / `traceLog` | 挂 hook（记录参数/返回值/耗时/调用栈，可改参数/返回值/this、条件命中）、摘除、列表、增量读日志 |
| 动态 hook | `deoptimize` | 反内联调用方，保证 hook 命中 |

> ⚠️ `invokeMethod` / `writeField` / `watchMethod` 等会**真实改变宿主状态**，请自行承担后果；只在 debug 包可用。

### 数据库直查示例

IM 消息库是 WCDB 加密的，抖M 直接解密（密钥由账号 uid 推导，任意账号的库都能开）：

```
queryDatabase: {"database":"encrypted_<uid>_im.db",
                "sql":"SELECT msg_server_id, conversation_id, created_time FROM msg ORDER BY created_time DESC LIMIT 3"}
→ 3 row(s):
  msg_server_id=76920xxxxxxxxxxxxxx, conversation_id=0:1:<对方uid>:<你的uid>, created_time=1790936562351
```

> IM 库的时间戳是毫秒（13 位）；个别扩展字段是秒（10 位），换算前先看位数。

## 🏗️ 构建

```bash
./gradlew assembleDebug          # 调试包（含逆向调试工具）
./gradlew assembleRelease        # 发布包（不含逆向调试工具）
```

- **两个包的区别**：调试包注册 33 个逆向调试工具（搜代码结构、运行时读写、动态 hook 等）；发布包用 `BuildConfig.DEBUG` 门控 + R8 裁剪，**这组工具不会编译进发布包**（不含相关类与字符串）
- 按 ABI 分包：`arm64-v8a` 与 `armeabi-v7a` 各出一个 APK
- 两个包使用**同一签名**（`release.keystore`），可互相覆盖安装不丢登录态
- `versionName` 写在 `app/build.gradle.kts`；`versionCode` = 提交数
- 版本号参与 DexKit 定位缓存键（模块或抖音任一升级，缓存自动失效重扫）

### 项目结构

```
doumcp/
  ModuleMain.kt / McpServerHost.kt / ModulePrefs.kt / SettingsInjector.kt
  core/      HostRuntime（宿主上下文/classLoader/模块实例单例）、DexKitSupport（定位+缓存）、
             Reflect、ModLog、DouToastHelper、ResolvedCache(FastKV)、ObjectDump、ValueFormat
  features/  按功能分包，每包 tool / bridge / resolver 三层
    account/ video/ comment/ search/ im/ system/ database/
    devtool/ 逆向调试（**仅 debug 包注册**）：DexKit 结构查询、运行时反射读写/构造/调用、
             动态 hook（观察+篡改）、堆栈/Activity/堆快照
```

## ⚠️ 已知限制

- 发送语音（silk 编码转码链路复杂）与发布作品（上传凭证+大量混淆字段）暂未实现
- 评论区风控：短时间连发评论可能被平台静默过滤（请求成功但不出现在列表）
- 需登录的功能未登录时返回错误；数据库写操作请自行承担后果

## 📄 免责声明

仅供学习与研究使用。使用者需遵守相关法律法规及抖音用户协议；作者不对使用本模块产生的任何损失或账号风险承担责任。
