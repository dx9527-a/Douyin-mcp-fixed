package io.github.yfishyon.doumcp.features.im.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * IM 消息读写桥。
 *
 * 消息读取：IM SDK 的消息 DAO 代理（全部未混淆语义方法），实例链：
 * `SDKManager.INSTANCE.getImSdkClient().getIIMSdkDaoService().getIMMsgDaoDelegate()`
 * - 首屏（最近 N 条）：`initMessageList(conversationId, limit)`
 * - 向旧翻页：`queryOlderMessageList(conversationId, orderIndex, minOrderIndex, limit)`
 *   （SQL 语义：order_index < orderIndex 且 >= minOrderIndex，按 order 倒序取 limit 条）
 *
 * 消息模型同样是语义 getter（getUuid/getMsgId/getContent/getSender/isRecalled 等）。
 */
object ImMsgBridge {
    private const val SDK_MANAGER = "com.bytedance.ies.im.core.sdk.SDKManager"
    private const val SDK_CONTEXT = "com.bytedance.im.core.mi.IMSdkContext"

    // 未混淆固定类名
    private const val CONV_LIST_API = "com.bytedance.ies.im.core.api.client.ConversationListModel"
    private const val TEXT_CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.model.TextContent"
    private const val GSON_UTIL = "com.bytedance.ies.im.core.api.utils.GsonUtil"
    private const val MULTI_INSTANCE_HELPER = "com.bytedance.ies.im.core.sdk.MultiInstanceHelperKt"
    private const val MESSAGE_UTILS = "com.bytedance.im.core.internal.utils.IMessageUtils"
    private const val MESSAGE_MODEL = "com.bytedance.im.core.model.Message"
    private const val REQUEST_LISTENER = "com.bytedance.im.core.client.callback.IRequestListener"

    /** 引用消息 proto（TextContent.referenceInfo 的类型）。 */
    private const val REFERENCE_INFO = "com.bytedance.im.core.proto.ReferenceInfo"

    /** 文本消息的协议类型（服务端协议枚举）。 */
    private const val MSG_TYPE_TEXT = 7

    /** 图片消息的协议类型（content 含 inline_pic 缩略图与资源元数据）。 */
    private const val MSG_TYPE_IMAGE = 27

    /** 发送回调等待时间。 */
    private const val SEND_TIMEOUT_SEC = 15L

    /** 单页默认条数上限（防御参数过大拖垮 DB）。 */
    private const val MAX_PAGE_SIZE = 200

    /**
     * 消息读取（首屏或按 orderIndex 向旧翻页，阻塞走数据库，调用方负责后台线程）。
     *
     * @param conversationId 会话 ID（从 getConversations 结果取）
     * @param cursor 翻页游标（null/0 取最近消息；翻页传上页返回的 nextCursor，即更旧消息的 orderIndex）
     * @param limit 每页条数（默认 20，最大 200）
     */
    fun getMessagesJson(
        conversationId: String,
        cursor: Long?,
        limit: Int,
        msgTypes: List<Int> = emptyList(),
        sender: String = "",
        keyword: String = "",
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val dao = resolveMsgDao() ?: return errorJson("消息数据层获取失败（IM 未初始化）")
        val daoClass = dao.javaClass

        val messages: List<*> =
            runCatching {
                if (cursor == null || cursor <= 0L) {
                    daoClass.methods
                        .firstOrNull {
                            it.name == "initMessageList" && it.parameterTypes.size == 2 &&
                                it.parameterTypes[0] == String::class.java
                        }?.invoke(dao, conversationId, pageSize) as? List<*>
                } else {
                    daoClass.methods
                        .firstOrNull {
                            it.name == "queryOlderMessageList" && it.parameterTypes.size == 4
                        }?.invoke(dao, conversationId, cursor, 0L, pageSize) as? List<*>
                }
            }.getOrElse {
                ModLog.e("getMessages 查询失败", it)
                null
            } ?: return errorJson("消息查询失败（DAO 方法未定位）")

        // DAO 返回新→旧，翻转为阅读顺序（旧→新）；模块层过滤（类型/发送者/文本）
        val ordered = messages.filterNotNull().reversed()
        val arr = JSONArray()
        for (msg in ordered) {
            runCatching { extractMessage(msg) }
                .getOrNull()
                ?.let { json ->
                    if (matchesMessage(json, msgTypes, sender, keyword)) arr.put(json)
                }
        }

        val out = JSONObject().put("ok", true).put("conversationId", conversationId)
        if (cursor != null && cursor > 0) out.put("cursor", cursor)
        out.put("count", arr.length())
        // 本页拿满说明可能还有更旧的消息
        val hasMore = ordered.size >= pageSize
        out.put("hasMore", hasMore)
        ordered.firstOrNull()?.let { oldest ->
            (Reflect.getter(oldest, "getOrderIndex") as? Number)?.let {
                if (hasMore) out.put("nextCursor", it.toLong())
            }
        }
        out.put("messages", arr)
        return out.toString()
    }

    /** 消息过滤：msgTypes 精确集合、sender 精确 uid、keyword 按文本模糊 */
    private fun matchesMessage(
        json: JSONObject,
        msgTypes: List<Int>,
        sender: String,
        keyword: String,
    ): Boolean {
        if (msgTypes.isNotEmpty() && json.optInt("msgType") !in msgTypes) return false
        if (sender.isNotEmpty() && json.optString("sender") != sender) return false
        if (keyword.isNotEmpty() && !json.optString("text").contains(keyword, ignoreCase = true)) return false
        return true
    }

    // ==================== 消息撤回 ====================

    /**
     * 撤回自己发的消息。
     *
     * 链路（与宿主长按撤回一致）：
     * 1. 消息 DAO 按 serverId 查回消息对象（校验归属会话）
     * 2. 消息工具接口上"消息对象 + 发送回调"签名的方法即为撤回入口
     *    （同签名还有入库/更新两个语义名方法，排除后唯一剩下的就是撤回，
     *    避免在代码里写混淆方法名）
     *
     * @param conversationId 会话 ID
     * @param messageId 消息的服务端 ID（getMessages 结果的 msgId）
     */
    fun revokeJson(
        conversationId: String,
        messageId: Long,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (messageId <= 0L) return errorJson("messageId 非法")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val message =
            findMessageById(conversationId, messageId)
                ?: return errorJson("消息不存在或不属于该会话（messageId=$messageId）")
        if (Reflect.getter(message, "isSelf") != true) return errorJson("只能撤回本账号发出的消息")
        if (isRecalledByExt(message)) return errorJson("消息已撤回")

        return runCatching {
            val utils = messageUtils() ?: return errorJson("消息发送工具获取失败")

            val utilsClass = Class.forName(MESSAGE_UTILS, false, HostRuntime.requireClassLoader())
            val messageClass = Class.forName(MESSAGE_MODEL, false, HostRuntime.requireClassLoader())
            val listenerClass = Class.forName(REQUEST_LISTENER, false, HostRuntime.requireClassLoader())
            val semanticNames = setOf("addMessage", "updateMessage")
            val recallMethod =
                utilsClass.methods
                    .filter {
                        it.parameterCount == 2 &&
                            it.parameterTypes[0] == messageClass &&
                            it.parameterTypes[1] == listenerClass
                    }.singleOrNull { it.name !in semanticNames }
                    ?: return errorJson("撤回方法未定位")

            val listener = SendListener(listenerClass)
            recallMethod.invoke(utils, message, listener.proxy)
            listener.await()?.let { return errorJson(it) }

            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("messageId", messageId)
                .toString()
        }.getOrElse {
            ModLog.e("revoke 失败", it)
            errorJson("撤回异常: ${it.cause ?: it}")
        }
    }

    // ==================== 消息编辑 ====================

    /**
     * 编辑自己发送的文本消息。
     *
     * 链路：消息 DAO 按 serverId 查回消息对象（校验归属）→ 反序列化 content 为
     * TextContent → setText 新文本 → 重新序列化并回填 content/contentObj →
     * 消息工具上语义方法 updateMessage(message, listener) 同步到服务端
     * （与撤回同签名，方法名未混淆可直接调用）。
     *
     * @param conversationId 会话 ID
     * @param messageId 消息的服务端 ID（getMessages 结果的 msgId）
     * @param newText 编辑后的新文本
     */
    fun editMessageJson(
        conversationId: String,
        messageId: Long,
        newText: String,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (messageId <= 0L) return errorJson("messageId 非法")
        if (newText.isBlank()) return errorJson("新文本为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val message =
            findMessageById(conversationId, messageId)
                ?: return errorJson("消息不存在或不属于该会话")
        if (isRecalledByExt(message)) return errorJson("消息已撤回，无法编辑")
        if ((Reflect.getter(message, "getMsgType") as? Number)?.toInt() != MSG_TYPE_TEXT) {
            return errorJson("仅支持编辑文本消息")
        }

        return runCatching {
            // 1. content JSON → TextContent → 换 text → 重新序列化
            val oldContent = Reflect.getter(message, "getContent")?.toString() ?: ""
            val contentClass = Class.forName(TEXT_CONTENT, false, HostRuntime.requireClassLoader())
            val content =
                runCatching {
                    val gsonClass = Class.forName(GSON_UTIL, false, HostRuntime.requireClassLoader())
                    val gson = gsonClass.getDeclaredField("INSTANCE").get(null)!!
                    gsonClass.methods
                        .first { it.name == "fromJson" && it.parameterCount == 2 }
                        .invoke(gson, oldContent, contentClass)
                }.getOrNull() ?: contentClass.getDeclaredConstructor().newInstance()
            contentClass.methods
                .firstOrNull { it.name == "setText" && it.parameterCount == 1 }
                ?.invoke(content, newText)

            val gsonClass = Class.forName(GSON_UTIL, false, HostRuntime.requireClassLoader())
            val gson = gsonClass.getDeclaredField("INSTANCE").get(null)!!
            val newContentJson =
                gsonClass.methods
                    .first { it.name == "toJson" && it.parameterCount == 1 }
                    .invoke(gson, content) as String

            // 2. 回填 content 与 contentObj
            Reflect.fieldsOf(message.javaClass)["content"]?.set(message, newContentJson)
            Reflect.fieldsOf(message.javaClass)["contentObj"]?.set(message, content)

            // 3. updateMessage 同步服务端（语义方法，与撤回同签名）
            val utils = messageUtils() ?: return errorJson("消息发送工具获取失败")

            val listenerClass = Class.forName(REQUEST_LISTENER, false, HostRuntime.requireClassLoader())
            val updateMethod =
                Class
                    .forName(MESSAGE_UTILS, false, HostRuntime.requireClassLoader())
                    .methods
                    .filter {
                        it.parameterCount == 2 &&
                            it.parameterTypes[0] == Class.forName(MESSAGE_MODEL, false, HostRuntime.requireClassLoader()) &&
                            it.parameterTypes[1] == listenerClass
                    }.singleOrNull { it.name == "updateMessage" }
                    ?: return errorJson("编辑方法未定位")

            val listener = SendListener(listenerClass)
            updateMethod.invoke(utils, message, listener.proxy)
            listener.await()?.let { return errorJson(it) }

            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("messageId", messageId)
                .put("newText", newText)
                .toString()
        }.getOrElse {
            ModLog.e("editMessage 失败", it)
            errorJson("编辑异常: ${it.cause ?: it}")
        }
    }

    // ==================== 已读 / 未读 ====================

    /**
     * 标记会话已读（读到最新）。
     *
     * 会话列表模型上的语义方法：`markConversationRead(conversationId)`。
     *
     * @param conversationId 会话 ID（从 getConversations 结果取）
     */
    fun markReadJson(conversationId: String): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val listModel =
            ImBridge.resolveConversationListModel()
                ?: return errorJson("会话列表模型获取失败（IM 未初始化）")
        return runCatching {
            // markConversationRead 是 void 方法：先定位再调用，不能用返回值判成败
            val method =
                listModel.javaClass.methods.firstOrNull {
                    it.name == "markConversationRead" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: return errorJson("已读方法未找到")
            method.invoke(listModel, conversationId)
            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .toString()
        }.getOrElse {
            ModLog.e("markRead 失败", it)
            errorJson("标记已读异常: ${it.cause ?: it}")
        }
    }

    /**
     * 全局未读统计：遍历全部会话，按各会话的未读数求和。
     * 免打扰会话的未读单独计出（不进角标总数，与宿主角标口径一致）。
     */
    fun unreadCountJson(): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val listModel =
            ImBridge.resolveConversationListModel()
                ?: return errorJson("会话列表模型获取失败（IM 未初始化）")
        val conversations =
            Reflect.getter(listModel, "getAllConversationSync") as? List<*>
                ?: return errorJson("会话列表读取失败")

        var total = 0L
        var muted = 0L
        var conversationsWithUnread = 0
        for (conv in conversations) {
            conv ?: continue
            val count =
                runCatching {
                    (Reflect.getter(conv, "getUnreadCount") as? Number)?.toLong() ?: 0L
                }.getOrDefault(0L)
            if (count <= 0L) continue
            total += count
            conversationsWithUnread++
            val isMuted =
                runCatching {
                    Reflect.getter(conv, "getMuted") as? Boolean
                }.getOrNull() ?: false
            if (isMuted) muted += count
        }

        return JSONObject()
            .put("ok", true)
            .put("totalUnread", total)
            .put("unreadExcludingMuted", total - muted)
            .put("mutedUnread", muted)
            .put("conversationsWithUnread", conversationsWithUnread)
            .toString()
    }

    /** 消息 DAO 代理：SDKManager → client → SDK 上下文 → daoService → delegate。 */
    private fun resolveMsgDao(): Any? {
        val sdkClient = imSdkClient() ?: return null
        // client 是外层封装，SDK 上下文（含 DAO 服务）在其唯一返回该类型的方法里
        val context = clientContext(sdkClient) ?: return null
        val daoService = Reflect.getter(context, "getIIMSdkDaoService") ?: return null
        return Reflect.getter(daoService, "getIMMsgDaoDelegate")
    }

    // ==================== 消息发送 ====================

    /**
     * 发送文本消息（单聊/群聊同链路，参数用 conversationId）。
     *
     * 构造流程与宿主发送逻辑一致（逆向确认）：
     * 1. 会话列表模型按 ID 取会话对象
     * 2. 构造 TextContent（无参构造 + setText），序列化成 content JSON
     * 3. 消息构造器：conversation(会话) + msgType(7) + content(JSON) → build()
     *   （build 自动生成 uuid/sender/createdAt/secSender，conversation 自动填会话三件套与排序索引）
     * 4. 回填 contentObj，补齐客户端发送 ext，交给消息工具发送并等回调
     *
     * @param conversationId 会话 ID（从 getConversations 结果取，群聊参数同样用 conversationId）
     * @param text 消息正文（支持 [表情名] 语法与 @）
     * @param quoteMessageId 被引用的消息 ID（0 表示不引用）
     * @param quoteNickname 引用块上显示的名字（空串默认取被引用者的备注/昵称）
     */
    fun sendTextJson(
        conversationId: String,
        text: String,
        quoteMessageId: Long,
        quoteNickname: String,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (text.isBlank()) return errorJson("消息内容为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val conversation =
            resolveConversation(conversationId)
                ?: return errorJson("会话不存在或未加载（conversationId=$conversationId）")

        return runCatching {
            var referenceInfoToAttach: Any? = null
            // 1. TextContent 实例（无参构造 + 语义 setter）
            val contentClass = Class.forName(TEXT_CONTENT, false, HostRuntime.requireClassLoader())
            val content = contentClass.getDeclaredConstructor().newInstance()
            val setText =
                contentClass.methods.firstOrNull { it.name == "setText" && it.parameterCount == 1 }
                    ?: return errorJson("文本 setter 未找到")
            setText.invoke(content, text)

            // 1.5 引用消息（回复某条消息）：以会话里已有的真实引用对象为模板（Wire proto 用
            // newBuilder 拷贝），替换 referenced_message_id 后 build。Builder 的 setter 全是
            // 混淆名且多个 Long setter 无法按名区分——对每个候选 setter 设测试值后 build
            // 出实例、读字段名验证（referenced_message_id 字段未混淆）来判定 setter。
            if (quoteMessageId > 0) {
                val quoted =
                    findMessageById(conversationId, quoteMessageId)
                        ?: return errorJson("被引用消息不存在（messageId=$quoteMessageId）")
                val quotedContent = Reflect.getter(quoted, "getContent")?.toString() ?: ""
                val senderUid = (Reflect.getter(quoted, "getSender") as? Number)?.toLong() ?: 0L
                val nickname =
                    quoteNickname.ifBlank {
                        // 默认取被引用消息发送者的名字（备注优先于昵称）
                        runCatching { ImUserBridge.resolve(senderUid.toString()) }.getOrNull()?.let {
                            it.optString("remarkName").ifBlank { it.optString("nickname") }
                        } ?: ""
                    }
                val hintJson =
                    JSONObject()
                        .put("content", runCatching { JSONObject(quotedContent).optString("text") }.getOrDefault(""))
                        .put("is_edit", false)
                        .put("itemId", "")
                        .put("nickname", nickname)
                        .put("refmsg_content", quotedContent)

                // 直接构造 proto 引用对象（Builder 无参构造 + 语义 setter，方法名=字段名）
                val refInfoClass = Class.forName(REFERENCE_INFO, false, HostRuntime.requireClassLoader())
                val builderClass = refInfoClass.declaredClasses.first { it.simpleName == "Builder" }
                val refBuilder = builderClass.getDeclaredConstructor().newInstance()
                builderClass.methods
                    .first { it.name == "referenced_message_id" && it.parameterCount == 1 }
                    .invoke(refBuilder, java.lang.Long.valueOf(quoteMessageId))
                builderClass.methods
                    .first { it.name == "hint" && it.parameterCount == 1 }
                    .invoke(refBuilder, hintJson.toString())
                builderClass.methods
                    .first { it.name == "ref_message_type" && it.parameterCount == 1 }
                    .invoke(refBuilder, java.lang.Long.valueOf(MSG_TYPE_TEXT.toLong()))
                // 宿主 proto 的 required 字段：不设置 build() 会抛 IllegalStateException。
                // 参数类型是宿主的 MessageStatus（Wire 生成消息类，非 Int），
                // 通过其无参 Builder 构造默认实例传入。
                val statusSetter =
                    builderClass.methods
                        .firstOrNull { it.name == "referenced_message_status" && it.parameterCount == 1 }
                if (statusSetter != null) {
                    val statusParamType = statusSetter.parameterTypes[0]
                    val statusInstance =
                        runCatching {
                            val sbClass = statusParamType.declaredClasses.first { it.simpleName == "Builder" }
                            val sb = sbClass.getDeclaredConstructor().newInstance()
                            statusParamType.methods
                                .first { it.name == "build" && it.parameterCount == 0 }
                                .invoke(sb)
                        }.getOrNull()
                    if (statusInstance != null) {
                        statusSetter.invoke(refBuilder, statusInstance)
                    } else {
                        // 兜底：字段带 ADAPTER/默认实例静态常量时直接用
                        val fallback =
                            statusParamType.fields.firstOrNull { it.type == statusParamType && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        fallback?.let { statusSetter.invoke(refBuilder, it.get(null)) }
                    }
                }
                referenceInfoToAttach =
                    builderClass.methods
                        .first { it.name == "build" && it.parameterCount == 0 }
                        .invoke(refBuilder)
            }

            // 2. content JSON（与宿主一致的序列化）
            val gsonClass = Class.forName(GSON_UTIL, false, HostRuntime.requireClassLoader())
            val gson = gsonClass.getDeclaredField("INSTANCE").get(null)!!
            val toJson =
                gsonClass.methods.firstOrNull {
                    it.name == "toJson" && it.parameterCount == 1
                } ?: return errorJson("消息序列化方法未找到")
            val contentJson = toJson.invoke(gson, content) as String

            // 3. 构造消息
            val builder = createMessageBuilder() ?: return errorJson("消息构造器获取失败")
            val buildMethods = builder.javaClass.methods
            buildMethods
                .first { it.name == "conversation" && it.parameterCount == 1 }
                .invoke(builder, conversation)
            buildMethods
                .first { it.name == "msgType" && it.parameterCount == 1 }
                .invoke(builder, MSG_TYPE_TEXT)
            buildMethods
                .first { it.name == "content" && it.parameterCount == 1 }
                .invoke(builder, contentJson)
            val message =
                buildMethods
                    .first { it.name == "build" && it.parameterCount == 0 }
                    .invoke(builder) ?: return errorJson("消息构造失败")

            // contentObj 回填（发送前非空校验用它；来源标记与宿主一致）
            Reflect.fieldsOf(message.javaClass)["contentObj"]?.set(message, content)
            Reflect.fieldsOf(message.javaClass)["contentUpdateFrom"]?.set(message, "message_builder")

            // 引用块挂在 Message 模型上（TextContent 上不携带）
            referenceInfoToAttach?.let {
                Reflect.fieldsOf(message.javaClass)["referenceInfo"]?.set(message, it)
            }

            // 4. 客户端发送 ext（与宿主发送链路一致）
            val now = System.currentTimeMillis()

            @Suppress("UNCHECKED_CAST")
            val ext = Reflect.getter(message, "getExt") as? MutableMap<String, String>
            ext?.put("old_client_message_id", now.toString())
            ext?.put("im_client_send_msg_time", now.toString())
            ext?.put("a:ntp_ready", "0")

            // 5. 发送（异步长连接，等回调）
            val utils = messageUtils() ?: return errorJson("消息发送工具获取失败")

            val listener = SendListener(Class.forName(REQUEST_LISTENER, false, HostRuntime.requireClassLoader()))
            val sendMethod =
                utils.javaClass.methods.firstOrNull {
                    it.name == "sendMessage" && it.parameterCount == 3
                } ?: return errorJson("发送方法未找到")
            sendMethod.invoke(utils, message, HashMap<String, String>(), listener.proxy)

            listener.await()?.let { return errorJson(it) }

            // 服务端 msgId 在回调返回的 Message 上；回调未带则读本地 message 的
            val sent = listener.sentMessage ?: message
            val msgId = (Reflect.getter(sent, "getMsgId") as? Number)?.toLong() ?: 0L
            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("uuid", Reflect.getter(message, "getUuid")?.toString() ?: "")
                .put("msgId", msgId)
                .put("text", text)
                .toString()
        }.getOrElse {
            ModLog.e("sendText 失败", it)
            errorJson("发送异常: ${it.cause ?: it}")
        }
    }

    // ==================== 图片发送 ====================

    // 未混淆固定类名（宿主 IM 服务层公开模型/接口）
    private const val PHOTO_PARAM = "com.ss.android.ugc.aweme.im.service.model.PhotoParam"
    private const val IM_SERVICE_MANAGER = "com.ss.android.ugc.aweme.im.sdk.service.IMServiceManager"
    private const val CHAT_ROOM_SERVICE = "com.ss.android.ugc.aweme.im.internal.IChatRoomService"

    /**
     * 发送本地图片到指定会话。
     *
     * 链路与宿主聊天页发图一致：宿主把整个流程托管给聊天室服务——
     * 模块只需构造图片参数（路径/mime/宽高），调用服务上的批量发送方法，
     * 内容构造（压缩/编码/md5）、上传队列、消息构建、发送全部由宿主完成（异步）。
     * 方法本身立即返回，发送结果需事后用 getMessages 轮询确认。
     *
     * @param conversationId 会话 ID（从 getConversations 结果取）
     * @param imagePath 图片绝对路径（如 /sdcard/DCIM/Camera/xxx.jpg）
     */
    fun sendImageJson(
        conversationId: String,
        imagePath: String,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (imagePath.isBlank()) return errorJson("图片路径为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val file = java.io.File(imagePath)
        if (!file.isFile) return errorJson("图片文件不存在: $imagePath")

        // 读图片宽高（只解码边界，不加载位图）
        val opts =
            android.graphics.BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imagePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return errorJson("不是可解码的图片文件")

        return runCatching {
            // 1. 图片参数（公开字段直接赋值；mime 按扩展名）
            val paramClass = Class.forName(PHOTO_PARAM, false, HostRuntime.requireClassLoader())
            val param = paramClass.getDeclaredConstructor().newInstance()
            val fields = Reflect.fieldsOf(paramClass)
            fields["path"]?.set(param, imagePath)
            fields["mime"]?.set(param, mimeFor(imagePath))
            fields["width"]?.set(param, opts.outWidth)
            fields["height"]?.set(param, opts.outHeight)

            // 2. 聊天室服务（静态入口，内部按服务接口从服务注册表取）
            val managerClass = Class.forName(IM_SERVICE_MANAGER, false, HostRuntime.requireClassLoader())
            val service =
                managerClass.methods
                    .firstOrNull {
                        java.lang.reflect.Modifier
                            .isStatic(it.modifiers) &&
                            it.parameterCount == 0 && it.returnType.name == CHAT_ROOM_SERVICE
                    }?.invoke(null) ?: return errorJson("聊天室服务获取失败（IM 未初始化）")

            // 3. 批量发图方法：接口上 (String, List, boolean, long, 回调) → String 唯一
            //    （boolean = 是否原图，宿主聊天页发图传 false）
            val itf = Class.forName(CHAT_ROOM_SERVICE, false, HostRuntime.requireClassLoader())
            val sendMethod =
                itf.methods.singleOrNull {
                    it.parameterCount == 5 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == List::class.java &&
                        it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes[3] == Long::class.javaPrimitiveType &&
                        it.returnType == String::class.java
                } ?: return errorJson("发图方法未定位")

            // 4. 宿主回调参数是抽象类（无法 Proxy），宿主自己也传 null——纯异步。
            //    同步化：发送前记录最新消息 ID，轮询消息列表等新图片消息落地，拿 msgId/资源元数据
            val before = latestSelfImageMsgId(conversationId).first
            sendMethod.invoke(service, conversationId, listOf(param), false, System.currentTimeMillis(), null)

            var msgId = 0L
            var imageMeta: JSONObject? = null
            for (attempt in 1..20) { // 最多 60 秒（大图上传慢）
                Thread.sleep(3000)
                val now = latestSelfImageMsgId(conversationId)
                if (now.first > before && now.first > 0) {
                    msgId = now.first
                    imageMeta = now.second
                    break
                }
            }
            if (msgId <= 0L) return errorJson("发送超时（10 秒内未观察到图片消息落地，可稍后用 getMessages 确认）")

            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("imagePath", imagePath)
                .put("width", opts.outWidth)
                .put("height", opts.outHeight)
                .put("msgId", msgId)
                .put("image", imageMeta ?: JSONObject())
                .toString()
        }.getOrElse {
            ModLog.e("sendImage 失败", it)
            errorJson("发图异常: ${it.cause ?: it}")
        }
    }

    private fun mimeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

    // ==================== 消息表态 ====================

    // 未混淆固定类名
    private const val MODIFY_MSG_PROPERTY_MSG = "com.bytedance.im.core.model.ModifyMsgPropertyMsg"
    private const val LOCAL_PROPERTY_ITEM = "com.bytedance.im.core.model.LocalPropertyItem"

    /** 表态属性 key 前缀（sticker emoji；系统 emoji 点赞另有 e: 前缀）。 */
    private const val PROPERTY_KEY_PREFIX = "se:"

    /**
     * 消息表态（添加/取消表情回应，与宿主长按消息表情面板同链路）。
     *
     * 链路：
     * 1. 按服务端 ID 查回消息对象（校验归属会话），取会话对象（ticket 必需）
     * 2. 构造属性修改请求：会话四件套 + 消息双 ID + 属性项
     *    （key = "se:" + 表情文本，value 空，幂等 ID = 本人 uid，deleted 标记添加/删除）
     * 3. 消息工具接口上"属性修改请求 + 发送回调"签名的方法发送（服务端协议为设置消息属性命令，
     *    回调在服务端确认后触发；同一签名仅此一个方法，避免写混淆名）
     *
     * @param conversationId 会话 ID（从 getConversations 结果取）
     * @param messageId 消息的服务端 ID（getMessages 结果的 msgId）
     * @param emoji 表情文本（如 [爱心]，多个用英文逗号分隔）
     * @param remove true=取消表态，false=添加表态
     */
    fun setMessageReactionJson(
        conversationId: String,
        messageId: Long,
        emoji: String,
        remove: Boolean,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (messageId <= 0L) return errorJson("messageId 非法")
        if (emoji.isBlank()) return errorJson("emoji 为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val message =
            findMessageById(conversationId, messageId)
                ?: return errorJson("消息不存在或不属于该会话（messageId=$messageId）")

        val conversation =
            resolveConversation(conversationId)
                ?: return errorJson("会话不存在或未加载（conversationId=$conversationId）")

        return runCatching {
            // 1. 属性修改请求（公开字段）
            val reqClass = Class.forName(MODIFY_MSG_PROPERTY_MSG, false, HostRuntime.requireClassLoader())
            val req = reqClass.getDeclaredConstructor().newInstance()
            val reqFields = Reflect.fieldsOf(reqClass)
            val convClass = conversation.javaClass
            reqFields["conversationId"]?.set(req, Reflect.getter(conversation, "getConversationId"))
            reqFields["conversationType"]?.set(req, Reflect.getter(conversation, "getConversationType"))
            reqFields["conversationShortId"]?.set(req, Reflect.getter(conversation, "getConversationShortId"))
            reqFields["ticket"]?.set(req, Reflect.getter(conversation, "getTicket"))
            reqFields["inboxType"]?.set(req, Reflect.getter(conversation, "getInboxType"))
            reqFields["serverMessageId"]?.set(req, Reflect.getter(message, "getMsgId"))
            reqFields["clientMessageId"]?.set(req, Reflect.getter(message, "getUuid"))

            // 2. 属性项（支持多个表情一次提交）：key 规范化（sticker 前缀），
            //    幂等 ID 用本人 uid，deleted 区分添加/取消
            val uid = AccountBridge.getCurrentUserId() ?: return errorJson("未登录")
            val itemClass = Class.forName(LOCAL_PROPERTY_ITEM, false, HostRuntime.requireClassLoader())
            val itemFields = Reflect.fieldsOf(itemClass)
            val addContent =
                reqClass.methods.firstOrNull {
                    it.name == "addPropertyContent" && it.parameterCount == 1
                } ?: return errorJson("属性项添加方法未找到")

            for (one in emoji.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                val key =
                    if (one.startsWith(PROPERTY_KEY_PREFIX) || one.startsWith("e:")) {
                        one
                    } else {
                        PROPERTY_KEY_PREFIX + one
                    }
                val item = itemClass.getDeclaredConstructor().newInstance()
                itemFields["msgUuid"]?.set(item, Reflect.getter(message, "getUuid"))
                itemFields["conversationId"]?.set(item, conversationId)
                itemFields["key"]?.set(item, key)
                itemFields["value"]?.set(item, "")
                itemFields["idempotent_id"]?.set(item, uid)
                itemFields["uid"]?.set(item, uid.toLong())
                itemFields["status"]?.set(item, 1)
                itemFields["create_time"]?.set(item, System.currentTimeMillis())
                itemFields["deleted"]?.set(item, if (remove) 1 else 0)
                addContent.invoke(req, item)
            }

            // 3. 发送（服务端命令异步，等回调）
            val utils = messageUtils() ?: return errorJson("消息发送工具获取失败")

            val listenerClass = Class.forName(REQUEST_LISTENER, false, HostRuntime.requireClassLoader())
            val sendMethod =
                utils.javaClass.methods.singleOrNull {
                    it.parameterCount == 2 &&
                        it.parameterTypes[0] == reqClass &&
                        it.parameterTypes[1] == listenerClass
                } ?: return errorJson("表态发送方法未定位")

            val listener = SendListener(listenerClass)
            sendMethod.invoke(utils, req, listener.proxy)
            listener.await()?.let { return errorJson(it) }

            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("messageId", messageId)
                .put("emojis", emoji.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                .put("action", if (remove) "unset" else "add")
                .toString()
        }.getOrElse {
            ModLog.e("setMessageReaction 失败", it)
            errorJson("表态异常: ${it.cause ?: it}")
        }
    }

    // ==================== 输入状态 ====================

    /**
     * 发送输入状态（对方聊天页顶部会显示"对方正在输入"）。
     *
     * 链路与宿主输入框一致：内容变化时经输入状态请求包装类发送，
     * 服务端长连接命令、无回调（fire-and-forget）。请求包装类全混淆，运行时按
     * "消息工具接口上唯一单参、参数类字段为 4 int + 1 String + 1 Serializable"定位。
     *
     * - 字段按类型+声明顺序赋值：唯一 String = 会话 ID；
     *   int 依次为 [命令类型(默认 12，跳过), 收件箱类型, 会话类型, 状态]
     *   （状态 3 = 输入中，4 = 停止，与宿主一致）
     *
     * @param conversationId 会话 ID（从 getConversations 结果取）
     * @param typing true=开始输入，false=立即停止
     * @param durationSec 自动停止秒数（仅 typing=true 时有效，到时自动发停止；0 表示只发开始不定时停止）
     */
    fun sendTypingStatusJson(
        conversationId: String,
        typing: Boolean,
        durationSec: Int,
    ): String {
        if (conversationId.isBlank()) return errorJson("会话 ID 为空")
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val conversation =
            resolveConversation(conversationId)
                ?: return errorJson("会话不存在或未加载（conversationId=$conversationId）")

        return runCatching {
            val utils = messageUtils() ?: return errorJson("消息发送工具获取失败")

            // 请求包装类 = 消息工具接口上唯一"单参、参数类字段结构为 4 int + 1 String + 1 Serializable"的方法
            val sendEntry =
                utils.javaClass.methods.singleOrNull { m ->
                    m.parameterCount == 1 && isTypingRequestClass(m.parameterTypes[0])
                } ?: return errorJson("输入状态发送方法未找到")

            val wrapperClass = sendEntry.parameterTypes[0]
            var intIndex = 0

            fun sendOne(typingNow: Boolean) {
                val wrapper = wrapperClass.getDeclaredConstructor().newInstance()
                intIndex = 0
                for (field in Reflect.fieldsOf(wrapperClass).values) {
                    when (field.type) {
                        String::class.java -> {
                            field.set(wrapper, conversationId)
                        }

                        Int::class.javaPrimitiveType -> {
                            // 第 1 个 int 是固定命令类型（构造器已设），跳过
                            if (intIndex > 0) {
                                field.set(
                                    wrapper,
                                    when (intIndex) {
                                        1 -> Reflect.getter(conversation, "getInboxType")
                                        2 -> Reflect.getter(conversation, "getConversationType")
                                        else -> if (typingNow) 3 else 4
                                    },
                                )
                            }
                            intIndex++
                        }
                    }
                }
                if (intIndex < 4) error("输入状态请求结构异常")
                // 无回调的异步发送，调用成功即指令已进入发送队列
                sendEntry.invoke(utils, wrapper)
            }

            sendOne(typing)

            if (typing && durationSec > 0) {
                // 定时自动停止（后台线程，不阻塞工具调用）
                Thread {
                    runCatching {
                        Thread.sleep(durationSec * 1000L)
                        sendOne(false)
                    }
                }.apply { isDaemon = true }.start()
            }

            JSONObject()
                .put("ok", true)
                .put("conversationId", conversationId)
                .put("typing", typing)
                .put("autoStopSec", if (typing) durationSec else 0)
                .toString()
        }.getOrElse {
            ModLog.e("sendTypingStatus 失败", it)
            errorJson("发送输入状态异常: ${it.cause ?: it}")
        }
    }

    /** 会话列表模型按 ID 取会话（与宿主取法一致）。 */
    private fun resolveConversation(conversationId: String): Any? {
        val apiClass = Class.forName(CONV_LIST_API, false, HostRuntime.requireClassLoader())
        val companion = apiClass.getDeclaredField("Companion").get(null)!!
        val inst = Reflect.getter(companion, "inst") ?: return null
        return Reflect.method(inst.javaClass, "getConversation", 1)?.invoke(inst, conversationId)
    }

    private fun createMessageBuilder(): Any? {
        val client = imSdkClient() ?: return null
        val beanService = Reflect.getter(client, "getBeanCreateService") ?: return null
        return Reflect.getter(beanService, "createMessageBuilder")
    }

    /** 输入状态请求包装类的结构特征：4 int + 1 String + 1 Serializable，可无参构造。 */
    private fun isTypingRequestClass(type: Class<*>): Boolean {
        if (runCatching { type.getDeclaredConstructor() }.isFailure) return false
        val fields = Reflect.fieldsOf(type).values
        val intCount = fields.count { it.type == Int::class.javaPrimitiveType }
        val stringCount = fields.count { it.type == String::class.java }
        val otherCount =
            fields.count {
                it.type != Int::class.javaPrimitiveType && it.type != String::class.java &&
                    java.io.Serializable::class.java.isAssignableFrom(it.type)
            }
        return intCount == 4 && stringCount == 1 && otherCount == 1
    }

    /**
     * 消息工具实例：辅助类上静态无参返回 [MESSAGE_UTILS] 的方法。
     * 发送/撤回/编辑/表态共用同一实例获取链路。
     */
    private fun messageUtils(): Any? =
        Class
            .forName(MULTI_INSTANCE_HELPER, false, HostRuntime.requireClassLoader())
            .methods
            .firstOrNull {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) &&
                    it.parameterCount == 0 && it.returnType.name == MESSAGE_UTILS
            }?.invoke(null)

    /** 发送回调：onSuccess 携带发送后的 Message（含服务端 msgId），onFailure 带错误描述。 */
    private class SendListener(
        type: Class<*>,
    ) {
        private val latch = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var error: String? = null
            private set

        /** 回调返回的 Message（服务端已分配 msgId）。 */
        @Volatile
        var sentMessage: Any? = null
            private set

        val proxy: Any =
            java.lang.reflect.Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
            ) { _, m, args ->
                when (m.name) {
                    "onSuccess" -> sentMessage = args?.firstOrNull()

                    "onFailure" -> error = "IM 返回失败"

                    // 成败之外不推进等待方：宿主回调真正到达前的对象访问不能被当作发送完成
                    else -> return@newProxyInstance null
                }
                // 成败都要放行等待方（否则失败要干等超时）
                latch.countDown()
                null
            }

        /** 等待回调；超时/失败返回错误描述。 */
        fun await(): String? {
            if (!latch.await(SEND_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)) {
                return "发送回调超时（${SEND_TIMEOUT_SEC}s）"
            }
            return error
        }
    }

    /** 按 ID 查消息（服务端 ID 全局查 + 校验归属会话）。 */
    private fun findMessageById(
        conversationId: String,
        messageId: Long,
    ): Any? {
        val dao = resolveMsgDao() ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val msgs =
                dao.javaClass.methods
                    .firstOrNull {
                        it.name == "getMsgByServerIdList" && it.parameterCount == 1
                    }?.invoke(dao, listOf(messageId)) as? List<*>
            msgs?.firstOrNull { msg ->
                msg != null && Reflect.getter(msg, "getConversationId")?.toString() == conversationId
            }
        }.getOrNull()
    }

    private fun imSdkClient(): Any? {
        val sdkClass = Class.forName(SDK_MANAGER, false, HostRuntime.requireClassLoader())
        val instance = sdkClass.getDeclaredField("INSTANCE").get(null)!!
        return Reflect.getter(instance, "getImSdkClient")
    }

    private fun clientContext(client: Any): Any? =
        client.javaClass.methods
            .firstOrNull {
                it.parameterCount == 0 && it.returnType.name == SDK_CONTEXT
            }?.invoke(client)

    /** 撤回标记：以 ext 的服务端协议键为准（模型上的同名判定方法状态不可靠）。 */
    private fun isRecalledByExt(msg: Any): Boolean {
        val extMap =
            (Reflect.getter(msg, "getExt") as? Map<*, *>)
                ?.mapKeys { it.key?.toString() ?: "" } ?: return false
        return extMap["s:is_recalled"] == "true" || extMap["s:recalled"] == "true"
    }

    /** 单条消息 → JSON（字段全部语义 getter，不依赖混淆名）。 */
    private fun extractMessage(msg: Any): JSONObject {
        val json = JSONObject()
        json.put("uuid", Reflect.getter(msg, "getUuid")?.toString() ?: "")
        (Reflect.getter(msg, "getMsgId") as? Number)?.let { json.put("msgId", it.toLong()) }
        json.put("msgType", (Reflect.getter(msg, "getMsgType") as? Number)?.toInt() ?: 0)
        (Reflect.getter(msg, "getCreatedAt") as? Number)?.takeIf { it.toLong() > 0 }?.let {
            json.put("createdAt", it.toLong())
            json.put("time", formatTime(it.toLong() / 1000))
        }
        (Reflect.getter(msg, "getSender") as? Number)?.let { json.put("sender", it.toLong()) }
        (Reflect.getter(msg, "isSelf") as? Boolean)?.let { json.put("isSelf", it) }
        (Reflect.getter(msg, "getMsgStatus") as? Number)?.let { json.put("msgStatus", it.toInt()) }
        (Reflect.getter(msg, "getReadStatus") as? Number)?.let { json.put("readStatus", it.toInt()) }
        if (isRecalledByExt(msg)) {
            json.put("recalled", true)
        }
        (Reflect.getter(msg, "getOrderIndex") as? Number)?.let { json.put("orderIndex", it.toLong()) }

        // 引用消息块（Message 模型字段，宿主回复链路写入）
        Reflect.field(msg, "referenceInfo")?.let { ref ->
            (Reflect.field(ref, "referenced_message_id") as? Long)?.takeIf { it > 0 }?.let {
                json.put("quoteMessageId", it)
            }
            (Reflect.field(ref, "hint") as? String)?.let { h ->
                runCatching { JSONObject(h).optString("content") }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { json.put("quoteContent", it) }
            }
        }

        // 消息正文：content 是序列化 JSON，按类型提取关键字段
        val content = Reflect.getter(msg, "getContent")?.toString() ?: ""
        if (content.isNotEmpty()) {
            val parsed = runCatching { JSONObject(content) }.getOrNull()
            if (parsed != null) {
                json.put("contentType", parsed.optInt("type", 0))
                // 引用消息（回复某条消息时）
                val quoteId = parsed.optLong("quote_message_id", 0L)
                if (quoteId > 0) json.put("quoteMessageId", quoteId)

                val msgType = json.optInt("msgType")
                when (msgType) {
                    // 文本：text 字段（含 [表情] 语法与 @）
                    7 -> {
                        parsed.optString("text").takeIf { it.isNotEmpty() }?.let { json.put("text", it) }
                    }

                    // 图片：资源元数据 + 缩略图
                    MSG_TYPE_IMAGE -> {
                        parsed.optJSONObject("resource_url")?.let { res ->
                            val meta = JSONObject()
                            for (k in listOf("md5", "oid", "skey", "width", "height", "data_size")) {
                                if (res.has(k)) meta.put(k, res.opt(k))
                            }
                            json.put("image", meta)
                        }
                        parsed.optString("inline_pic").takeIf { it.isNotEmpty() }?.let {
                            json.put("inlinePicBase64", it.take(64) + "…（${it.length} 字符）")
                        }
                        json.put("isLongPic", parsed.optBoolean("is_long_pic", false))
                    }

                    // 大表情包：表情 id 与尺寸
                    5 -> {
                        json.put("stickerId", parsed.optString("id"))
                        for (k in listOf("width", "height", "display_name", "emoji_source")) {
                            val v = parsed.opt(k)
                            if (v != null && v.toString().isNotEmpty()) json.put(k, v)
                        }
                    }

                    // 分享卡片（作品/评论/合集）：标题 + 封面 + 链接
                    8, 105, 110 -> {
                        parsed.optString("aweme_title").takeIf { it.isNotEmpty() }?.let { json.put("title", it) }
                        parsed.optString("content_name").takeIf { it.isNotEmpty() }?.let { json.put("contentName", it) }
                        parsed.optString("comment").takeIf { it.isNotEmpty() }?.let { json.put("comment", it) }
                        // 封面/内嵌资源带 url_list（数组或单对象两种形态）
                        for (resKey in listOf("cover_url", "image_url", "resource_url")) {
                            parsed.optJSONObject(resKey)?.let { res ->
                                val url =
                                    res.optJSONArray("url_list")?.optString(0)
                                        ?: res.optJSONObject("url_list")?.optString("url")
                                url?.takeIf { it.isNotEmpty() }?.let { json.put("cover", it) }
                            }
                        }
                        parsed.optString("schema")?.let { if (it.isNotEmpty()) json.put("schema", it) }
                    }

                    // 其他类型：输出顶层标量键值（不过滤，有多少给多少）
                    else -> {
                        val extra = JSONObject()
                        for (key in parsed.keys()) {
                            val v = parsed.opt(key)
                            if (v != null && v !is JSONObject && v !is org.json.JSONArray) {
                                extra.put(key, v)
                            }
                        }
                        if (extra.length() > 0) json.put("contentData", extra)
                    }
                }
            } else {
                json.put("text", content)
            }
        }
        return json
    }

    private fun formatTime(seconds: Long): String =
        java.text
            .SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(seconds * 1000))

    /** 该会话里自己发的最新一条图片消息（msgId + 资源元数据），轮询落地用。 */
    private fun latestSelfImageMsgId(conversationId: String): Pair<Long, JSONObject?> {
        return runCatching {
            val list = JSONObject(getMessagesJson(conversationId, null, 20))
            val messages = list.optJSONArray("messages") ?: return@runCatching 0L to null
            for (i in messages.length() - 1 downTo 0) {
                val m = messages.optJSONObject(i) ?: continue
                if (m.optInt("msgType") == MSG_TYPE_IMAGE && m.optBoolean("isSelf")) {
                    return@runCatching m.optLong("msgId") to m.optJSONObject("image")
                }
            }
            0L to null
        }.getOrDefault(0L to null)
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
