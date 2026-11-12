package io.airtap.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Button;
import android.widget.Toast;

/** An optional example, copied only on request. Never starts a phone task or records success. */
final class FirstTask {
    static final String PROMPT = "用已保存的 Airtap 连接，打开手机浏览器查三个值得去的西湖景点，整理到手机便签里。"
            + "保存后重新打开，确认三个景点和来源都在，再告诉我保存在哪里。"
            + "先检查已有 App；可以使用现有的同类 App，不覆盖原内容，不发送或发布。"
            + "如果这条任务已经完成，先核对已有结果，不重复新建。";

    private FirstTask() {}

    static Button button(Activity owner) {
        Button button = new Button(owner);
        button.setText("交给 AI 第一件事");
        button.setFilterTouchesWhenObscured(true);
        AppUi.button(button, AppUi.PRIMARY);
        button.setOnClickListener(view -> new AlertDialog.Builder(owner)
                .setTitle("试着让 AI 跨 App 做件事")
                .setMessage(PROMPT + "\n\n复制后发到原来的 AI 对话。你也可以直接交代自己的任务；连接成功不等于任务已经完成。")
                .setPositiveButton("复制任务", (dialog, which) -> {
                    owner.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Airtap 第一件任务", PROMPT));
                    Toast.makeText(owner, "已复制，回到自己的 AI 对话粘贴", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("返回", null).show());
        return button;
    }
}
