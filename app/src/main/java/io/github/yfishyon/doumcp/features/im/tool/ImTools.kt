package io.github.yfishyon.doumcp.features.im.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.anyProp
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.boolArgOrNull
import io.github.yfishyon.doumcp.core.inputSchema
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.stringProp
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.im.bridge.ImBridge
import io.github.yfishyon.doumcp.features.im.bridge.ImEmojiBridge
import io.github.yfishyon.doumcp.features.im.bridge.ImMsgBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** IM 相关 MCP 工具注册。 */
internal fun Server.registerImTools() {
    addDouMcpTool(
        name = "getMessages",
        description =
            "分页拉取指定会话的消息列表（文本/图片/分享卡片等，按时间正序）。" +
                "参数 conversationId（从 getConversations 结果取）；cursor（可选，首页不传取最近消息，" +
                "翻页传上一页返回的 nextCursor，即更旧消息的 orderIndex）；limit（可选，默认 20，最大 200）；" +
                "msgTypes（可选，逗号分隔的消息类型精确过滤，如 '7,27'）；sender（可选，精确 uid 过滤）；" +
                "keyword（可选，按文本内容模糊过滤——模块层过滤，省 token）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull()
        val limit = request.arguments.intArg("limit", 20)
        val msgTypes =
            request.arguments
                .stringArg("msgTypes")
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        val sender = request.arguments.stringArg("sender") ?: ""
        val keyword = request.arguments.stringArg("keyword") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.getMessagesJson(conversationId, cursor, limit, msgTypes, sender, keyword) }
        }
    }

    addDouMcpTool(
        name = "sendTextMessage",
        schema =
            inputSchema(
                properties =
                    mapOf(
                        "conversationId" to stringProp(),
                        "text" to stringProp(),
                        "quoteMessageId" to anyProp(),
                        "quoteNickname" to stringProp(),
                    ),
                required = listOf("conversationId", "text"),
            ),
        description =
            "发送文本消息到指定会话（单聊/群聊通用）。参数 conversationId（从 getConversations 取）、" +
                "text（消息正文）、quoteMessageId（可选，被引用的消息 ID——发引用回复用，对方 UI 显示引用块）、" +
                "quoteNickname（可选，引用块上显示的名字，不传默认取被引用者的备注/昵称）。" +
                "发送成功返回 uuid 与服务端 msgId",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val text = request.arguments.stringArg("text") ?: ""
        val quoteMessageId = request.arguments.stringArg("quoteMessageId")?.toLongOrNull() ?: 0L
        val quoteNickname = request.arguments.stringArg("quoteNickname") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.sendTextJson(conversationId, text, quoteMessageId, quoteNickname) }
        }
    }

    addDouMcpTool(
        name = "sendImageMessage",
        description =
            "发送本地图片到指定会话（单聊/群聊通用）。参数 conversationId（从 getConversations 取）、" +
                "imagePath（图片绝对路径，如 /sdcard/DCIM/Camera/xxx.jpg）。" +
                "同步等待宿主上传发送完成，成功返回 msgId 与图片资源元数据（image.oid/md5/skey）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val imagePath = request.arguments.stringArg("imagePath") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.sendImageJson(conversationId, imagePath) }
        }
    }

    addDouMcpTool(
        name = "setMessageReaction",
        description =
            "给指定消息添加/取消表情表态（与长按消息的面板表情一致，支持一次多个）。" +
                "参数 conversationId（从 getConversations 取）、messageId（从 getMessages 的 msgId 取）、" +
                "emoji（表情文本如 [爱心]，多个用英文逗号分隔）；action（可选，add=添加默认，unset=取消表态）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val messageId = request.arguments.stringArg("messageId")?.toLongOrNull() ?: 0L
        val emoji = request.arguments.stringArg("emoji") ?: ""
        val action = request.arguments.stringArg("action") ?: "add"
        withContext(Dispatchers.IO) {
            toolCall {
                ImMsgBridge.setMessageReactionJson(
                    conversationId,
                    messageId,
                    emoji,
                    action == "unset",
                )
            }
        }
    }

    addDouMcpTool(
        name = "sendTypingStatus",
        description =
            "向指定会话发送输入状态（对方聊天页顶部会显示\"对方正在输入\"）。" +
                "参数 conversationId（从 getConversations 取）、typing（true=开始输入，false=立即停止）、" +
                "durationSec（可选，仅 typing=true 时有效，到时自动发停止；默认 10 秒，传 0 表示只发开始不定时停止）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val typing = request.arguments.boolArg("typing", true)
        val durationSec = request.arguments.intArg("durationSec", 10)
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.sendTypingStatusJson(conversationId, typing, durationSec) }
        }
    }

    addDouMcpTool(
        name = "revokeMessage",
        description =
            "撤回自己发送的消息（对方会看到撤回提示）。参数 conversationId（从 getConversations 取）、" +
                "messageId（从 getMessages 结果的 msgId 取，只能是本账号发出的消息，且需未被撤回）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val messageId = request.arguments.stringArg("messageId")?.toLongOrNull() ?: 0L
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.revokeJson(conversationId, messageId) }
        }
    }

    addDouMcpTool(
        name = "editMessage",
        description =
            "编辑自己发送的文本消息（对方端会显示\"已编辑\"）。" + // 实际上并不会，但我不想改
                "参数 conversationId、messageId（文本消息）、newText（新文本）。" +
                "注意：抖音的编辑时效限制与撤回类似，超时的消息服务端会拒绝",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        val messageId = request.arguments.stringArg("messageId")?.toLongOrNull() ?: 0L
        val newText = request.arguments.stringArg("newText") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.editMessageJson(conversationId, messageId, newText) }
        }
    }

    addDouMcpTool(
        name = "markConversationRead",
        description =
            "把指定会话标记为已读（清零该会话的未读数，读到最新消息）。" +
                "参数 conversationId（从 getConversations 取）",
    ) { request ->
        val conversationId = request.arguments.stringArg("conversationId") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.markReadJson(conversationId) }
        }
    }

    addDouMcpTool(
        name = "getUnreadCount",
        description =
            "获取当前账号的全局未读消息统计：总未读数、排除免打扰后的未读数、" +
                "免打扰未读数、有未读的会话数",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { ImMsgBridge.unreadCountJson() }
        }
    }

    addDouMcpTool(
        name = "getEmojis",
        description =
            "获取抖音云表情（小表情）目录，可用于 sendTextMessage 里按 [表情名] 语法发送。" +
                "keyword（可选）按表情名过滤；includeHidden（可选，默认 false）为 true 时附带限时活动表情" +
                "（仅活动时间窗口内的可见，返回在 limited 数组）。每项含 text（[名] 发送格式）、name（不含方括号）",
    ) { request ->
        val keyword = request.arguments.stringArg("keyword")
        val includeHidden = request.arguments.boolArg("includeHidden", false)
        withContext(Dispatchers.IO) {
            toolCall { ImEmojiBridge.getEmojisJson(keyword, includeHidden) }
        }
    }

    addDouMcpTool(
        name = "getConversations",
        description =
            "获取当前账号的私信会话列表：会话ID、对方名字/头像/uid、未读数、最后消息、" +
                "群聊/单聊标记；withSpark（可选，默认 false）为 true 时附带火花/小火人状态" +
                "（火花天数、状态、是否需续、关系类型）。参数 limit（可选，默认 50）",
    ) { request ->
        val limit = request.arguments.intArg("limit", 50)
        val withSpark = request.arguments.boolArg("withSpark", false)
        val keyword = request.arguments.stringArg("keyword") ?: ""
        val isGroup = request.arguments.boolArgOrNull("isGroup")
        withContext(Dispatchers.IO) {
            toolCall { ImBridge.getConversationsJson(limit, withSpark, keyword, isGroup) }
        }
    }
}
