package com.doudou.x.ui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
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
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.data.ConversationStore;
import com.doudou.x.data.SessionManager;
import com.doudou.x.data.UiSettingsStore;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.Conversation;
import com.doudou.x.ui.login.LoginActivity;
import com.doudou.x.ui.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话主页：聊天列表 + 输入栏 + 侧边栏（历史对话 / 账号 / 设置）。
 */
public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private TextView tvTitle;
    private LinearLayout layoutEmpty;
    private EditText etInput;
    private ImageButton btnSend;
    private TextView tvAccount;
    private TextView tvHistoryEmpty;

    private RecyclerView recyclerChat;
    private RecyclerView recyclerHistory;

    private ChatAdapter chatAdapter;
    private HistoryAdapter historyAdapter;

    private AiEngine mockEngine;
    private OpenAiEngine openAiEngine;
    private ApiConfigStore apiConfig;
    private UiSettingsStore uiSettings;
    private ConversationStore store;
    private SessionManager session;

    private Conversation currentConversation;
    private boolean streaming = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session = SessionManager.getInstance(this);
        if (!session.isLoggedIn()) {
            goLogin();
            return;
        }
        store = ConversationStore.getInstance(this);
        apiConfig = ApiConfigStore.getInstance(this);
        uiSettings = UiSettingsStore.getInstance(this);
        mockEngine = new MockAiEngine();
        openAiEngine = new OpenAiEngine(apiConfig);

        initViews();
        initChatList();
        initDrawer();

        // 恢复最近一段对话，否则开启新对话
        List<Conversation> history = store.loadAll();
        if (!history.isEmpty()) {
            loadConversation(history.get(0));
        } else {
            startNewConversation();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        tvTitle = findViewById(R.id.tvTitle);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        recyclerChat = findViewById(R.id.recyclerChat);

        ImageButton btnMenu = findViewById(R.id.btnMenu);
        ImageButton btnNewChatTop = findViewById(R.id.btnNewChatTop);

        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
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

    private void initChatList() {
        chatAdapter = new ChatAdapter();
        chatAdapter.setOnAiMessageLongClickListener(
                new ChatAdapter.OnAiMessageLongClickListener() {
                    @Override
                    public void onAiMessageLongClick(ChatMessage message) {
                        showRawResponseDialog(message);
                    }
                });
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
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerChat.setLayoutManager(layoutManager);
        recyclerChat.setAdapter(chatAdapter);
    }

    private void initDrawer() {
        tvAccount = findViewById(R.id.tvAccount);
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty);
        recyclerHistory = findViewById(R.id.recyclerHistory);
        LinearLayout btnNewChat = findViewById(R.id.btnNewChat);
        LinearLayout layoutAccount = findViewById(R.id.layoutAccount);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        tvAccount.setText(session.getMaskedPhone());

        historyAdapter = new HistoryAdapter(new HistoryAdapter.OnConversationClickListener() {
            @Override
            public void onClick(Conversation conversation) {
                loadConversation(conversation);
                drawerLayout.closeDrawer(GravityCompat.START);
            }

            @Override
            public void onLongClick(final Conversation conversation) {
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

    /** 按当前配置选择引擎：配置齐全走真实 API，否则本地模拟。 */
    private AiEngine pickEngine() {
        return apiConfig.isReady() ? openAiEngine : mockEngine;
    }

    private void cancelEngines() {
        mockEngine.cancel();
        openAiEngine.cancel();
    }

    private void startNewConversation() {
        cancelEngines();
        streaming = false;
        updateSendButtonState();
        currentConversation = new Conversation();
        chatAdapter.setMessages(currentConversation.getMessages());
        tvTitle.setText(R.string.chat_title_default);
        historyAdapter.setSelectedId(null);
        refreshEmptyState();
        refreshHistoryList();
    }

    private void loadConversation(Conversation conversation) {
        cancelEngines();
        streaming = false;
        updateSendButtonState();
        currentConversation = conversation;
        chatAdapter.setMessages(conversation.getMessages());
        conversation.deriveTitleIfNeeded();
        tvTitle.setText(conversation.getTitle());
        historyAdapter.setSelectedId(conversation.getId());
        refreshEmptyState();
        scrollToBottom();
    }

    private void onSendClicked() {
        if (streaming) {
            return;
        }
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (apiConfig.isEnabled() && !apiConfig.isReady()) {
            Toast.makeText(this, R.string.api_config_incomplete, Toast.LENGTH_SHORT).show();
        }
        etInput.setText("");

        // 用户消息
        ChatMessage userMessage = new ChatMessage(ChatMessage.ROLE_USER, text);
        currentConversation.addMessage(userMessage);
        chatAdapter.addMessage(userMessage);

        // 占位 AI 消息，进入流式输出
        final ChatMessage aiMessage = new ChatMessage(ChatMessage.ROLE_AI, "");
        aiMessage.setStreaming(true);
        currentConversation.addMessage(aiMessage);
        chatAdapter.addMessage(aiMessage);
        refreshEmptyState();
        scrollToBottom();

        streaming = true;
        updateSendButtonState();

        List<ChatMessage> historySnapshot =
                new ArrayList<>(currentConversation.getMessages());
        pickEngine().streamReply(historySnapshot, new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
                // 思考结束，即将开始输出
            }

            @Override
            public void onToken(String fullText) {
                chatAdapter.updateLastMessage(fullText, true);
                scrollToBottom();
            }

            @Override
            public void onComplete(String fullText, String rawResponse) {
                finishStreaming(fullText, rawResponse);
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                finishStreaming("⚠️ " + errorMessage, rawResponse);
            }

            private void finishStreaming(String finalText, String rawResponse) {
                streaming = false;
                aiMessage.setRawResponse(rawResponse);
                chatAdapter.updateLastMessage(finalText, false);
                updateSendButtonState();

                // 生成标题并持久化
                currentConversation.deriveTitleIfNeeded();
                tvTitle.setText(currentConversation.getTitle());
                store.upsert(currentConversation);
                refreshHistoryList();
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

        int pad = dp(16);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextSize(12);
        textView.setTypeface(Typeface.MONOSPACE);
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void confirmDeleteConversation(final Conversation conversation) {
        new AlertDialog.Builder(this)
                .setMessage(conversation.getTitle())
                .setPositiveButton("删除", (dialog, which) -> {
                    store.delete(conversation.getId());
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

    private void refreshHistoryList() {
        List<Conversation> history = store.loadAll();
        historyAdapter.setConversations(history);
        tvHistoryEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshEmptyState() {
        layoutEmpty.setVisibility(
                chatAdapter.getMessageCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void updateSendButtonState() {
        btnSend.setEnabled(!streaming);
        btnSend.setAlpha(streaming ? 0.4f : 1.0f);
    }

    private void scrollToBottom() {
        int count = chatAdapter.getMessageCount();
        if (count > 0) {
            recyclerChat.scrollToPosition(count - 1);
        }
    }

    private void goLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.isLoggedIn()) {
            // 从设置页返回（可能改了渲染方式、清空了历史或退出登录）
            applyCodeRenderMode();
            chatAdapter.notifyDataSetChanged();
            refreshHistoryList();
            tvAccount.setText(session.getMaskedPhone());
            if (currentConversation != null) {
                boolean stillExists = false;
                for (Conversation c : store.loadAll()) {
                    if (c.getId().equals(currentConversation.getId())) {
                        stillExists = true;
                        break;
                    }
                }
                if (!stillExists && !currentConversation.getMessages().isEmpty()) {
                    startNewConversation();
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        cancelEngines();
        super.onDestroy();
    }
}
