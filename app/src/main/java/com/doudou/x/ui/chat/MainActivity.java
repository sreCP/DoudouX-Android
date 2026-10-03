package com.doudou.x.ui.chat;

/**
 * ========== 本文件在干什么 ==========
 *
 * 对话主页。整个 App 的三个界面区域都由它托管：
 *
 *   1. 中间 —— 聊天区（RecyclerView + 输入框 + 发送按钮）
 *   2. 顶部 —— 标题栏（当前对话标题 / 菜单 / 新建）
 *   3. 侧边 —— 抽屉（历史会话列表、账号、设置入口）
 *
 * ========== 它在架构里的位置 ==========
 *
 * 这是一个「什么都干一点」的 Activity，是本项目目前唯一的业务编排点：
 *
 *   MainActivity
 *     ├─ 持有三套引擎：mockEngine（没配接口时的假回复）
 *     │                openAiEngine（主对话，注入了工具执行器）
 *     │                titleEngine（只用来生成对话标题，不注入工具）
 *     ├─ 持有三个持久化 Store：ConversationStore / ApiConfigStore / UiSettingsStore
 *     └─ 负责把「引擎回调」翻译成「界面动作」：
 *          流式 token → 节流后刷最后一条气泡
 *          工具调用   → 画工具卡片
 *          完成/出错  → 落盘、刷新侧边栏、按需生成标题
 *
 * ========== 三个容易踩坑的机制（改代码前务必先看） ==========
 *
 * 【一】流式节流
 *   引擎每收到一个 token 就回调一次 onToken，频率可能是每秒几十次。
 *   这里不直接刷 UI，而是把内容暂存到 pendingStreamText，
 *   用 uiHandler 延迟 STREAM_UI_INTERVAL_MS(80ms) 统一刷一次。
 *   → 效果：界面每秒最多刷 12 次，肉眼看起来依然是连续的。
 *
 * 【二】流式快速通道
 *   刷 UI 时优先调用 ViewHolder.updateStreamingContent()，它只做 setText，
 *   不重新解析 Markdown、不重建视图结构。
 *   只有当它返回 false（结构变了，比如出现了工具卡片）才退回完整绑定。
 *   → 这是解决「思考过程渲染导致卡顿 / 气泡被顶飞」的关键，别改成每次都全量刷新。
 *
 * 【三】自动滚动的让位
 *   autoScrollToBottom 标记：用户手动往上滑时置 false，停止自动跟随；
 *   用户发新消息或切会话时重置为 true。
 *   → 否则用户想回看上面的内容时会被不断拽回底部。
 *
 * ========== 生命周期 ==========
 *
 *   onCreate  → 登录校验 → 初始化 Store/引擎 → initViews/initChatList/initDrawer → 开新会话
 *   onResume  → 从设置页返回时重刷渲染方式、侧边栏、账号，并处理「历史被清空」的情况
 *   onDestroy → 取消所有引擎 + 移除未执行的节流任务
 */
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.doudou.x.R;
import com.doudou.x.ai.AiEngine;
import com.doudou.x.ai.MockAiEngine;
import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.ai.context.ContextManager;
import com.doudou.x.ai.ToolExecutor;
import com.doudou.x.ai.ToolRegistry;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.data.ConversationStore;
import com.doudou.x.data.SessionManager;
import com.doudou.x.data.UiSettingsStore;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.Conversation;
import com.doudou.x.model.ToolCall;
import com.doudou.x.ui.login.LoginActivity;
import com.doudou.x.ui.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话主页：聊天列表 + 输入栏 + 侧边栏（历史对话 / 账号 / 设置）。
 */
public class MainActivity extends AppCompatActivity {

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    /** 根布局，侧边抽屉的容器。返回键优先关抽屉就是问它 isDrawerOpen。 */
    private DrawerLayout drawerLayout;
    /** 顶部标题，显示当前对话标题。 */
    private TextView tvTitle;
    /** 空状态占位（还没有任何消息时显示兜兜的欢迎语）。 */
    private LinearLayout layoutEmpty;
    /** 底部输入框。 */
    private EditText etInput;
    /** 发送按钮。流式输出期间会置灰 + 半透明。 */
    private ImageButton btnSend;
    /** 侧边栏上的手机号（脱敏后的）。 */
    private TextView tvAccount;
    /** 侧边栏「还没有历史对话」提示。 */
    private TextView tvHistoryEmpty;

    /** 聊天列表。 */
    private RecyclerView recyclerChat;
    /** 侧边栏的历史会话列表。 */
    private RecyclerView recyclerHistory;
    /** 聊天列表的 LayoutManager，滚到底部时要用到它。 */
    private LinearLayoutManager chatLayoutManager;

    // ------------------------------------------------------------------
    // 适配器
    // ------------------------------------------------------------------

    private ChatAdapter chatAdapter;
    private HistoryAdapter historyAdapter;

    // ------------------------------------------------------------------
    // 引擎与存储
    // ------------------------------------------------------------------

    /** 未配置接口时的本地模拟引擎，保证 App 装上去就能对话。 */
    private AiEngine mockEngine;
    /** 真实接口引擎（主对话）。 */
    private OpenAiEngine openAiEngine;
    /** 单独用于生成对话标题，避免与主对话的流式输出互相打断。 */
    private OpenAiEngine titleEngine;
    /** 接口配置（多套 profile + 各项开关）。 */
    private ApiConfigStore apiConfig;
    /** 界面偏好（代码块换行模式、自动生成标题开关）。 */
    private UiSettingsStore uiSettings;
    /** 会话持久化（SharedPreferences）。 */
    private ConversationStore store;
    /** 登录态。 */
    private SessionManager session;

    // ------------------------------------------------------------------
    // 会话状态
    // ------------------------------------------------------------------

    /** 当前正在编辑的会话。注意它可能是还没落盘的新会话。 */
    private Conversation currentConversation;
    /**
     * 是否正在流式输出。为 true 时发送按钮禁用，且 onSendClicked 直接 return，
     * 避免用户连点导致两条请求并发。
     */
    private boolean streaming = false;

    /**
     * 流式刷新节流：每 STREAM_UI_INTERVAL_MS 毫秒刷新一次。
     * 增量更新只做 setText，单次开销很小，所以间隔可以比之前更短。
     */
    private static final long STREAM_UI_INTERVAL_MS = 80;
    /** 主线程 Handler，用来排节流任务和取消它们。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 待刷新的回答全文（引擎回调的最新值）。 */
    private String pendingStreamText;
    /** 待刷新的思考过程全文。 */
    private String pendingStreamThinking;
    /** 是否已经有一个节流任务排在队列里了。true 时新到的 token 只需更新数据、不必再排一次。 */
    private boolean streamUpdateScheduled;
    /** 节流任务的真正执行体。 */
    private final Runnable streamFlushRunnable = new Runnable() {
        @Override
        public void run() {
            streamUpdateScheduled = false;
            // 先把数据落到消息对象上
            chatAdapter.setLastMessageData(pendingStreamText, pendingStreamThinking);
            // 优先走增量更新：只 setText，不重建视图结构
            boolean handled = false;
            ChatMessage last = chatAdapter.getLastMessage();
            int lastIndex = chatAdapter.getMessageCount() - 1;
            if (last != null && lastIndex >= 0) {
                // 直接取当前屏幕上可见的那个 ViewHolder。
                // 注意：如果最后一项已经滑出屏幕，findViewHolderForAdapterPosition 返回 null，
                // 这时也走下面的兜底分支，不会有任何问题。
                RecyclerView.ViewHolder holder =
                        recyclerChat.findViewHolderForAdapterPosition(lastIndex);
                if (holder instanceof ChatAdapter.MessageViewHolder) {
                    // 返回 false 表示结构变了（比如新出现了工具卡片），需要完整绑定
                    handled = ((ChatAdapter.MessageViewHolder) holder)
                            .updateStreamingContent(last, pendingStreamText,
                                    pendingStreamThinking);
                }
            }
            if (!handled) {
                // 兜底：走完整绑定（会重新解析 Markdown，开销较大，但结构一定正确）
                chatAdapter.updateLastMessage(pendingStreamText, pendingStreamThinking, true);
            }
            scrollToBottom();
        }
    };
    /** 用户手动上滑离开底部时暂停自动滚动。 */
    private boolean autoScrollToBottom = true;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 登录校验：没登录直接跳登录页并结束自己，后面的初始化都不做
        session = SessionManager.getInstance(this);
        if (!session.isLoggedIn()) {
            goLogin();
            return;
        }

        // ---- 三个 Store 都是基于 SharedPreferences 的单例 ----
        store = ConversationStore.getInstance(this);
        apiConfig = ApiConfigStore.getInstance(this);
        uiSettings = UiSettingsStore.getInstance(this);

        // ---- 三套引擎 ----
        mockEngine = new MockAiEngine();
        openAiEngine = new OpenAiEngine(apiConfig);
        // 主对话允许使用本地工具；生成标题的请求不带工具，避免小模型乱调用
        openAiEngine.setToolExecutor(new ToolExecutor() {
            @Override
            public String execute(String name, String argumentsJson) {
                // 转交给 ToolRegistry 统一分发。工具都是只读的，同步执行即可
                return ToolRegistry.execute(name, argumentsJson);
            }
        });
        // titleEngine 刻意不 setToolExecutor，所以它永远不会触发 Function Calling
        titleEngine = new OpenAiEngine(apiConfig);
        // 上下文模块初始化：内部自带一个独立的引擎做历史摘要压缩
        ContextManager.get().init(this);

        initViews();
        initChatList();
        initDrawer();

        // 每次打开都从全新对话开始；历史对话仍保留在侧边栏里
        startNewConversation();
    }

    /**
     * 绑定主界面的控件并设置三个按钮的点击。
     */
    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        tvTitle = findViewById(R.id.tvTitle);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        recyclerChat = findViewById(R.id.recyclerChat);

        ImageButton btnMenu = findViewById(R.id.btnMenu);
        ImageButton btnNewChatTop = findViewById(R.id.btnNewChatTop);

        // 左上菜单：拉开侧边抽屉
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        // 右上新建：不开抽屉，直接原地开一个新会话
        btnNewChatTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startNewConversation();
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendClicked();
            }
        });
    }

    /**
     * 初始化聊天列表。这里集中了所有为了「流式输出不卡顿」做的配置。
     */
    private void initChatList() {
        chatAdapter = new ChatAdapter();
        chatLayoutManager = new LinearLayoutManager(this);
        // 从底部开始堆叠：内容不足一屏时消息贴底显示，符合聊天应用的观感
        chatLayoutManager.setStackFromEnd(true);
        recyclerChat.setLayoutManager(chatLayoutManager);
        recyclerChat.setAdapter(chatAdapter);
        // 流式输出会频繁改变最后一项高度，默认动画会导致闪烁/短暂空白
        // → 直接关掉 ItemAnimator，这是最省事也最有效的做法
        recyclerChat.setItemAnimator(null);
        // 加大缓存：气泡类型多（用户/AI/工具卡片），缓存少了会频繁 onCreateViewHolder
        recyclerChat.setItemViewCacheSize(8);
        recyclerChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // 用户手动离开底部时停止自动滚动，避免和手势互相打断
                // canScrollVertically(1) 为 false 表示已经到底、不能再往下滑了
                autoScrollToBottom = !recyclerView.canScrollVertically(1);
            }
        });
        // 长按 AI 气泡 → 查看原始响应
        chatAdapter.setOnAiMessageLongClickListener(
                new ChatAdapter.OnAiMessageLongClickListener() {
                    @Override
                    public void onAiMessageLongClick(ChatMessage message) {
                        showRawResponseDialog(message);
                    }
                });
        // 代码块右上角复制按钮
        chatAdapter.setOnCodeCopyListener(new ChatAdapter.OnCodeCopyListener() {
            @Override
            public void onCopyCode(String code) {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("code", code));
                Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });
        applyCodeRenderMode();
    }

    /**
     * 初始化侧边抽屉：账号区、历史列表、新建会话、设置入口。
     */
    private void initDrawer() {
        tvAccount = findViewById(R.id.tvAccount);
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty);
        recyclerHistory = findViewById(R.id.recyclerHistory);
        LinearLayout btnNewChat = findViewById(R.id.btnNewChat);
        LinearLayout layoutAccount = findViewById(R.id.layoutAccount);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        tvAccount.setText(session.getMaskedPhone());

        // 历史列表的三个动作：点击切换 / 重命名 / 删除
        // 注意 HistoryAdapter 内部已取消长按，全部走按钮，避免与抽屉手势冲突
        historyAdapter = new HistoryAdapter(new HistoryAdapter.OnConversationActionListener() {
            @Override
            public void onClick(Conversation conversation) {
                loadConversation(conversation);
                drawerLayout.closeDrawer(GravityCompat.START);
            }

            @Override
            public void onRename(Conversation conversation) {
                showRenameDialog(conversation);
            }

            @Override
            public void onDelete(Conversation conversation) {
                confirmDeleteConversation(conversation);
            }
        });
        recyclerHistory.setLayoutManager(new LinearLayoutManager(this));
        recyclerHistory.setAdapter(historyAdapter);

        btnNewChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startNewConversation();
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        });
        // 设置入口有两个：齿轮按钮和整个账号区，共用同一个监听器
        View.OnClickListener settingsClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        };
        btnSettings.setOnClickListener(settingsClick);
        layoutAccount.setOnClickListener(settingsClick);
    }

    // ------------------------------------------------------------------
    // 对话逻辑
    // ------------------------------------------------------------------

    /** 应用代码块渲染方式（单行横滚 / 自动换行黑灰相间）。 */
    private void applyCodeRenderMode() {
        chatAdapter.setCodeRenderMode(uiSettings.isCodeWrapEnabled()
                ? CodeBlockRenderer.MODE_WRAP
                : CodeBlockRenderer.MODE_SINGLE_LINE);
    }

    /**
     * 按当前配置选择引擎：配置齐全走真实 API，否则本地模拟。
     * <p>
     * 注意判断的是 isReady()（地址+key+model 都有值）而不是 isEnabled()，
     * 所以哪怕用户开了接口开关，配置不全时也会静默降级到 Mock，不至于崩。
     */
    private AiEngine pickEngine() {
        return apiConfig.isReady() ? openAiEngine : mockEngine;
    }

    /**
     * 取消三套引擎上正在进行的请求。切会话、开新会话、销毁时都要调。
     */
    private void cancelEngines() {
        mockEngine.cancel();
        openAiEngine.cancel();
        if (titleEngine != null) {
            titleEngine.cancel();
        }
    }

    /**
     * 开一个全新会话。
     * <p>
     * 注意：新会话此时【还没落盘】。只有等第一条回答结束（finishStreaming 里 store.upsert）
     * 或者用户手动触发保存时才会写进 SharedPreferences。
     * 这样设计是为了避免用户打开 App 什么都不说就退出，留下一堆空会话。
     */
    private void startNewConversation() {
        cancelEngines();
        streaming = false;
        updateSendButtonState();
        currentConversation = new Conversation();
        // 把适配器的数据源换成新会话的消息列表
        chatAdapter.setMessages(currentConversation.getMessages());
        tvTitle.setText(R.string.chat_title_default);
        // 新会话没有 id 对应，取消侧边栏的选中态
        historyAdapter.setSelectedId(null);
        refreshEmptyState();
        refreshHistoryList();
    }

    /**
     * 从侧边栏加载一个历史会话。
     */
    private void loadConversation(Conversation conversation) {
        cancelEngines();
        streaming = false;
        // 切会话时恢复自动滚动：用户切过来就是要看最新的内容
        autoScrollToBottom = true;
        updateSendButtonState();
        currentConversation = conversation;
        chatAdapter.setMessages(conversation.getMessages());
        // 兜底：老数据可能没标题（只存了消息没存 title），这里用首条用户消息补一个
        conversation.deriveTitleIfNeeded();
        tvTitle.setText(conversation.getTitle());
        historyAdapter.setSelectedId(conversation.getId());
        refreshEmptyState();
        scrollToBottom();
    }

    /**
     * 点击发送：整个 App 最核心的一条交互链路。
     * <p>
     * 流程：
     *   1. 守卫（正在流式 / 输入为空 → 直接返回）
     *   2. 清空输入框、加用户气泡、加一个空的 AI 占位气泡
     *   3. 拷贝一份历史快照交给引擎（避免引擎异步期间历史被改动导致并发问题）
     *   4. 注册回调，把引擎事件翻译成界面动作
     */
    private void onSendClicked() {
        // 流式输出期间禁止再发，引擎同一时刻只能处理一个请求
        if (streaming) {
            return;
        }
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        // 开了接口开关但配置不全：提示一下，但仍然继续（会降级到 Mock 引擎）
        if (apiConfig.isEnabled() && !apiConfig.isReady()) {
            Toast.makeText(this, R.string.api_config_incomplete, Toast.LENGTH_SHORT).show();
        }
        etInput.setText("");
        // 发出新消息后恢复自动跟随底部
        autoScrollToBottom = true;

        // 用户消息
        ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, text);
        currentConversation.addMessage(userMessage);
        chatAdapter.addMessage(userMessage);

        // 占位 AI 消息，进入流式输出
        // setStreaming(true) 会让适配器走流式快速通道（不解析 Markdown）
        final ChatMessage aiMessage = new ChatMessage(ChatMessage.ROLE_AI, "");
        aiMessage.setStreaming(true);
        currentConversation.addMessage(aiMessage);
        chatAdapter.addMessage(aiMessage);
        refreshEmptyState();
        scrollToBottom();

        streaming = true;
        updateSendButtonState();

        // 快照：引擎在子线程异步跑，期间 currentConversation 可能被用户切走，
        // 传快照可以保证这一轮请求用到的历史是发送那一刻确定的
        List<ChatMessage> historySnapshot =
                new ArrayList<>(currentConversation.getMessages());
        pickEngine().streamReply(historySnapshot, new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
                // 思考结束，即将开始输出
                // 目前不做任何事：占位气泡已经在发送时就加好了
            }

            @Override
            public void onToken(String fullText, String thinkingText) {
                // 节流：累计到一定间隔再刷新，避免每个 token 都整条重建视图
                // 这里只更新数据，真正的刷新交给 streamFlushRunnable
                pendingStreamText = fullText;
                pendingStreamThinking = thinkingText;
                // 已经排了一次就别再排了，否则间隔会被不断推后，永远刷不出来
                if (streamUpdateScheduled) {
                    return;
                }
                streamUpdateScheduled = true;
                uiHandler.postDelayed(streamFlushRunnable, STREAM_UI_INTERVAL_MS);
            }

            @Override
            public void onToolCall(List<ToolCall> calls) {
                // 工具已在本机执行完，这里只负责展示
                aiMessage.setToolCalls(calls);
                // 工具卡片是结构性变化，必须走完整绑定（第三个参数 true）
                chatAdapter.updateLastMessage(aiMessage.getContent(),
                        aiMessage.getThinking(), true);
                scrollToBottom();
            }

            @Override
            public void onComplete(String fullText, String thinkingText, String rawResponse) {
                finishStreaming(fullText, thinkingText, rawResponse, false);
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                // 标记成错误信息：界面单独展示，且不会作为上下文回传给接口
                // （OpenAiEngine.buildRequestBody 里会跳过 isError() 的消息）
                finishStreaming(errorMessage, null, rawResponse, true);
            }

            /**
             * 流式结束的统一收口。成功和失败都走这里，只是 error 标记不同。
             *
             * @param finalText    最终文本（成功=完整回答，失败=错误描述）
             * @param thinkingText 思考过程，失败时为 null
             * @param rawResponse  原始响应，供长按查看
             * @param error        是否为错误结束
             */
            private void finishStreaming(String finalText, String thinkingText,
                                         String rawResponse, boolean error) {
                streaming = false;
                // 取消未执行的节流刷新，立即用最终结果刷新一次
                // 不做这一步的话，可能最终结果被 80ms 后的一次旧值刷新覆盖掉
                uiHandler.removeCallbacks(streamFlushRunnable);
                streamUpdateScheduled = false;
                aiMessage.setRawResponse(rawResponse);
                aiMessage.setThinking(thinkingText);
                aiMessage.setError(error);
                // 第三个参数 false：不再是流式，走完整 Markdown 渲染
                chatAdapter.updateLastMessage(finalText, thinkingText, false);
                updateSendButtonState();

                // 先用首条提问兜底生成标题并持久化
                // 这样即使后面模型生成标题失败，侧边栏也不会是一条无名会话
                currentConversation.deriveTitleIfNeeded();
                tvTitle.setText(currentConversation.getTitle());
                store.upsert(currentConversation);
                refreshHistoryList();

                // 成功后按需让模型生成摘要标题
                if (!error) {
                    maybeGenerateTitle(text, finalText);
                }
            }
        });
    }

    /** 长按 AI 消息：查看 API 原始返回字符串。 */
    private void showRawResponseDialog(ChatMessage message) {
        String raw = message.getRawResponse();
        if (TextUtils.isEmpty(raw)) {
            raw = getString(R.string.raw_empty);
        }
        final String content = raw;

        // 纯代码造一个可滚动、可选中的等宽文本视图
        int pad = dp(16);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextSize(12);
        textView.setTypeface(Typeface.MONOSPACE);
        // 允许长按选中，方便用户自己复制其中一小段
        textView.setTextIsSelectable(true);
        textView.setPadding(pad, pad, pad, pad);
        textView.setTextColor(0xFF1F2430);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
                .setTitle(R.string.raw_title)
                .setView(scrollView)
                .setPositiveButton(R.string.action_copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("raw_response", content));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    /** dp → px。 */
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 侧边栏重命名按钮：修改历史对话标题。 */
    private void showRenameDialog(final Conversation conversation) {
        final EditText input = new EditText(this);
        input.setText(conversation.getTitle());
        input.setSingleLine(true);
        input.setHint(R.string.history_rename_hint);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        // 光标放到末尾，方便接续输入
        if (!TextUtils.isEmpty(conversation.getTitle())) {
            input.setSelection(conversation.getTitle().length());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.history_rename)
                .setView(input)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.history_rename_empty, Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    conversation.setTitle(name);
                    store.upsert(conversation);
                    // 如果改的正好是当前会话，顶部标题也要同步
                    if (currentConversation != null
                            && currentConversation.getId().equals(conversation.getId())) {
                        tvTitle.setText(name);
                    }
                    refreshHistoryList();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 侧边栏删除按钮：二次确认后删除。 */
    private void confirmDeleteConversation(final Conversation conversation) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_delete)
                .setMessage(getString(R.string.history_delete_message, conversation.getTitle()))
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    store.delete(conversation.getId());
                    // 删的是当前会话 → 直接开一个新的；否则只刷新列表
                    if (currentConversation != null
                            && currentConversation.getId().equals(conversation.getId())) {
                        startNewConversation();
                    } else {
                        refreshHistoryList();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // 自动生成对话标题
    // ------------------------------------------------------------------

    /**
     * 首次对话（一问一答）结束后，若开关打开且真实接口可用，
     * 就让模型根据这一轮内容生成不超过 20 字的摘要作为标题。
     * <p>
     * 这道「额外一次请求」是可选的：失败就保留首条消息兜底的标题，完全不影响主对话。
     */
    private void maybeGenerateTitle(String userText, String aiText) {
        if (!uiSettings.isAutoTitleEnabled() || !apiConfig.isReady()) {
            return; // 开关关闭或未配置真实接口时，沿用首条用户消息
        }
        // 只在第一轮对话后生成：消息数超过 2（一问一答）就不再改标题，
        // 避免聊了半天标题突然变掉，也省掉不必要的请求
        if (currentConversation == null || currentConversation.getMessages().size() > 2) {
            return; // 只在第一轮对话后生成
        }
        if (TextUtils.isEmpty(userText) || TextUtils.isEmpty(aiText)) {
            return;
        }
        // 截断超长内容，避免摘要请求本身消耗太多 token
        String userPart = userText.length() > 300 ? userText.substring(0, 300) : userText;
        String aiPart = aiText.length() > 800 ? aiText.substring(0, 800) : aiText;
        // 提示词模板见 strings.xml 的 auto_title_prompt，含 {user} {model} 两个占位
        String prompt = getString(R.string.auto_title_prompt, userPart, aiPart);
        List<ChatMessage> request = new ArrayList<>();
        request.add(new ChatMessage(ChatMessage.ROLE_USER, prompt));

        // 用 titleEngine 而不是 openAiEngine：
        // 两者是独立的引擎实例，互不打断；且 titleEngine 没注入工具执行器
        titleEngine.streamReply(request, new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
                // 标题请求是后台静默的，不需要任何界面反馈
            }

            @Override
            public void onToken(String fullText, String thinkingText) {
                // 同上，不需要边生成边显示
            }

            @Override
            public void onToolCall(List<ToolCall> calls) {
                // 生成标题不需要工具，忽略
                // （理论上不会走到这里，因为 titleEngine 没有注入 ToolExecutor）
            }

            @Override
            public void onComplete(String fullText, String thinkingText, String rawResponse) {
                applyGeneratedTitle(fullText);
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                // 生成失败就保留原标题，不影响对话本身
            }
        });
    }

    /**
     * 清洗模型返回的标题文本并应用。
     * <p>
     * 小模型很容易「话多」，所以这里要做的清洗：
     *   换行压平 → 去各种引号 → 去「摘要：」类前缀 → 截断 20 字 → 空则放弃
     */
    private void applyGeneratedTitle(String raw) {
        if (currentConversation == null || TextUtils.isEmpty(raw)) {
            return;
        }
        String title = raw.trim()
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .replace("「", "")
                .replace("」", "")
                .replace("『", "")
                .replace("』", "")
                .trim();
        // 去掉「摘要：」「标题：」这类前缀
        String[] prefixes = {"对话摘要：", "对话标题：", "摘要：", "标题："};
        for (String prefix : prefixes) {
            if (title.startsWith(prefix)) {
                title = title.substring(prefix.length()).trim();
                break;
            }
        }
        // 硬截断 20 字，和提示词里要求的长度保持一致
        if (title.length() > 20) {
            title = title.substring(0, 20);
        }
        if (title.isEmpty()) {
            return;
        }
        currentConversation.setTitle(title);
        tvTitle.setText(title);
        store.upsert(currentConversation);
        refreshHistoryList();
    }

    /** 刷新侧边栏历史列表，并按需显示「还没有历史对话」。 */
    private void refreshHistoryList() {
        List<Conversation> history = store.loadAll();
        historyAdapter.setConversations(history);
        tvHistoryEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 没有消息时显示空状态占位，有消息时隐藏。 */
    private void refreshEmptyState() {
        layoutEmpty.setVisibility(
                chatAdapter.getMessageCount() == 0 ? View.VISIBLE : View.GONE);
    }

    /** 流式期间禁用发送按钮（置灰 + 半透明）。 */
    private void updateSendButtonState() {
        btnSend.setEnabled(!streaming);
        btnSend.setAlpha(streaming ? 0.4f : 1.0f);
    }

    /**
     * 滚到底部。最后一项高于一屏时 scrollToPosition 只会把它的顶部对齐到顶部，
     * 所以额外补一次到底滚动，保证最新内容可见。
     * <p>
     * 为什么要补一次：scrollToPosition 只保证「目标项在可见范围内」，
     * 对于一个比屏幕还高的气泡，它会把气泡顶部对齐到列表顶部，
     * 结果气泡的下面一截还在屏幕外，看起来就像没滚到底。
     */
    private void scrollToBottom() {
        int count = chatAdapter.getMessageCount();
        // 没有消息，或者用户手动上滑取消了自动跟随 → 什么都不做
        if (count == 0 || !autoScrollToBottom) {
            return;
        }
        // 第一步：滚动到最后一个位置
        if (chatLayoutManager != null) {
            chatLayoutManager.scrollToPosition(count - 1);
        } else {
            recyclerChat.scrollToPosition(count - 1);
        }
        // 第二步：post 到下一帧，等布局完成后再测量并补滚溢出部分
        recyclerChat.post(new Runnable() {
            @Override
            public void run() {
                if (!autoScrollToBottom || chatLayoutManager == null) {
                    return;
                }
                int lastIndex = chatAdapter.getMessageCount() - 1;
                if (lastIndex < 0) {
                    return;
                }
                // 拿不到 View 说明这一项已经不在屏幕上了（可能刚切了会话），放弃补滚
                View lastView = chatLayoutManager.findViewByPosition(lastIndex);
                if (lastView == null) {
                    return;
                }
                // 溢出量 = 最后一项底边 超出 列表可视底部 的距离
                int overflow = lastView.getBottom()
                        - (recyclerChat.getHeight() - recyclerChat.getPaddingBottom());
                if (overflow > 0) {
                    recyclerChat.scrollBy(0, overflow);
                }
            }
        });
    }

    /** 跳登录页，并清空任务栈（用户回退不会再回到主页）。 */
    private void goLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * 从设置页返回时刷新一切可能被改过的东西。
     * <p>
     * 需要重刷的：代码块渲染方式、整个列表（可能改了主题/设置）、
     * 侧边栏（可能在设置里清空了历史）、账号（可能退出登录了）。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.isLoggedIn()) {
            // 从设置页返回（可能改了渲染方式、清空了历史或退出登录）
            applyCodeRenderMode();
            chatAdapter.notifyDataSetChanged();
            refreshHistoryList();
            tvAccount.setText(session.getMaskedPhone());
            // 特殊处理：当前会话如果在设置页里被清空了历史，
            // 它已经不在 store 里了，但界面还显示着 → 开一个新会话
            if (currentConversation != null) {
                boolean stillExists = false;
                for (Conversation c : store.loadAll()) {
                    if (c.getId().equals(currentConversation.getId())) {
                        stillExists = true;
                        break;
                    }
                }
                // 注意后半句：还没落盘的新会话本来就不在 store 里，这种不能清掉
                if (!stillExists && !currentConversation.getMessages().isEmpty()) {
                    startNewConversation();
                }
            }
        }
    }

    /** 返回键：抽屉开着就先关抽屉，否则走系统默认（退出）。 */
    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    /** 销毁：取消所有请求，并移除可能还排在队列里的节流任务，避免引用已销毁的 Adapter。 */
    @Override
    protected void onDestroy() {
        cancelEngines();
        uiHandler.removeCallbacks(streamFlushRunnable);
        super.onDestroy();
    }
}
