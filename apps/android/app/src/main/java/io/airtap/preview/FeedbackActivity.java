package io.airtap.preview;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import okhttp3.Call;

/** Owner-only report management. This Activity never submits reports or gathers device content. */
public final class FeedbackActivity extends Activity {
    private final FeedbackRequestGate requests = new FeedbackRequestGate();
    private static final String LIST_UNAVAILABLE = "暂时无法读取问题反馈，请稍后刷新重试。";
    private EnrollmentStore.Config identity;
    private LinearLayout content;
    private ScrollView scroll;
    private Call call;
    private boolean visible, busy, identityChanged, deleteUncertain;
    private String cursor, selectedId, message = "";
    private List<String> history = new ArrayList<>();
    private FeedbackReport.Page page;
    private FeedbackReport.Detail detail;

    @Override public void onCreate(Bundle saved) {
        setTheme(R.style.AirtapTheme);
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        LinearLayout root = AppUi.root(this);
        root.addView(AppUi.header(this, AppUi.text(this, "问题反馈", 18), button("返回", AppUi.QUIET, v -> back())));
        scroll = new ScrollView(this);
        content = AppUi.column(this, 24);
        // Report content remains in memory only; Android must not retain view text in saved state.
        content.setSaveFromParentEnabled(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override public void onResume() {
        super.onResume(); visible = true;
        EnrollmentStore.Config current = readIdentity();
        if (!usable(current)) { unavailableIdentity(); return; }
        if (identity != null && !same(identity, current)) { unavailableIdentity(); return; }
        if (identityChanged) { render(); return; }
        identity = current;
        requests.resume(identity.url, identity.token);
        if (selectedId == null) loadPage(cursor, history); else loadDetail(selectedId);
    }

    @Override public void onPause() {
        visible = false;
        requests.pause(); cancel(); busy = false;
        page = null; detail = null;
        content.removeAllViews();
        super.onPause();
    }
    @Override public void onDestroy() {
        requests.pause(); cancel();
        identity = null; page = null; detail = null;
        super.onDestroy();
    }
    private void back() {
        if (selectedId == null || identityChanged) { finish(); return; }
        selectedId = null; detail = null; deleteUncertain = false; message = "";
        loadPage(cursor, history);
    }
    private EnrollmentStore.Config readIdentity() {
        try { return new EnrollmentStore(this).read(); }
        catch (Exception unavailable) { return null; }
    }
    private static boolean usable(EnrollmentStore.Config value) { return value != null && value.enrolled() && value.publicService(); }
    private static boolean same(EnrollmentStore.Config a, EnrollmentStore.Config b) {
        return usable(a) && usable(b) && a.url.equals(b.url) && a.token.equals(b.token);
    }
    private void unavailableIdentity() {
        identityChanged = true; requests.pause(); cancel(); busy = false;
        page = null; detail = null;
        message = "设备连接信息已变化或暂时无法读取。请返回 Airtap，重新打开问题反馈。";
        render();
    }
    private FeedbackRequestGate.Ticket begin() {
        if (!visible || isFinishing() || isDestroyed() || identityChanged) return null;
        if (!same(identity, readIdentity())) { unavailableIdentity(); return null; }
        cancel(); busy = true;
        return requests.begin();
    }
    private boolean current(FeedbackRequestGate.Ticket ticket) {
        if (!visible || isFinishing() || isDestroyed()) return false;
        EnrollmentStore.Config now = readIdentity();
        if (!same(identity, now)) { unavailableIdentity(); return false; }
        return requests.accepts(ticket, now.url, now.token);
    }
    private void cancel() { if (call != null) call.cancel(); call = null; }

    private void loadPage(String requestedCursor, List<String> requestedHistory) {
        FeedbackRequestGate.Ticket ticket = begin(); if (ticket == null) return;
        List<String> path = new ArrayList<>(requestedHistory);
        detail = null; render();
        call = FeedbackApi.list(ticket, requestedCursor, (body, status, code) -> {
            if (!current(ticket)) return;
            busy = false; call = null;
            try {
                if (code != null) throw new IllegalArgumentException();
                FeedbackReport.Page received = new FeedbackReport.Page(body);
                if (received.nextCursor != null && (received.nextCursor.equals(requestedCursor) || path.contains(received.nextCursor))) throw new IllegalArgumentException();
                page = received; cursor = requestedCursor; history = path;
                if (LIST_UNAVAILABLE.equals(message)) message = "";
            } catch (Exception unavailable) { message = LIST_UNAVAILABLE; }
            render(); scroll.smoothScrollTo(0, 0);
        });
    }
    private void loadDetail(String id) {
        FeedbackRequestGate.Ticket ticket = begin(); if (ticket == null) return;
        selectedId = id; detail = null; message = ""; render();
        call = FeedbackApi.detail(ticket, id, (body, status, code) -> {
            if (!current(ticket)) return;
            busy = false; call = null;
            if ("report_deleted".equals(code) || "report_not_found".equals(code)) {
                message = "report_deleted".equals(code) ? "这份报告已删除。" : "这份报告已到期或不存在。";
            } else {
                try {
                    if (code != null) throw new IllegalArgumentException();
                    detail = new FeedbackReport.Detail(body, id);
                    deleteUncertain = false;
                } catch (Exception unavailable) {
                    message = deleteUncertain ? "删除结果暂未确认，请稍后刷新查看。" : "暂时无法读取这份报告，请稍后刷新重试。";
                }
            }
            render(); scroll.smoothScrollTo(0, 0);
        });
    }
    private void delete() {
        if (busy || detail == null || deleteUncertain) return;
        FeedbackRequestGate.Ticket ticket = begin(); if (ticket == null) return;
        String id = detail.summary.id;
        // A transport cancellation cannot tell whether a DELETE already reached the server.
        deleteUncertain = true; message = "正在删除这份报告…"; render();
        call = FeedbackApi.delete(ticket, id, (body, status, code) -> {
            if (!current(ticket)) return;
            busy = false; call = null;
            if (code == null && FeedbackReport.deleted(body, id)) {
                selectedId = null; detail = null; page = null; deleteUncertain = false;
                message = "这份报告已删除。";
                loadPage(cursor, history);
            } else {
                message = "删除结果暂未确认，请刷新查看。不会自动重复删除。";
                render();
            }
        });
    }

    private void render() {
        if (!visible) return;
        content.removeAllViews();
        content.addView(text("你的 AI 整理并提交问题。报告私密保存，你可以查看回复或删除；默认保留 30 天。", 14));
        if (!message.isEmpty()) content.addView(text(message, 15));
        if (identityChanged) return;
        if (selectedId == null) renderList(); else renderDetail();
    }
    private void renderList() {
        action(content, busy ? "正在读取…" : "刷新", AppUi.SECONDARY, v -> { message = ""; loadPage(cursor, history); }, !busy);
        if (page == null) return;
        if (page.reports.isEmpty()) content.addView(text(history.isEmpty() ? "还没有问题反馈。遇到问题时，可以让你的 AI 整理并提交。" : "这一页暂时没有报告。你可以返回上一页。", 16));
        for (FeedbackReport.Summary report : page.reports) {
            LinearLayout row = AppUi.group(content);
            row.addView(text(report.title, 18));
            row.addView(text(FeedbackText.status(report.status) + " · " + date(report.createdAt), 14));
            action(row, "查看详情", AppUi.SECONDARY, v -> loadDetail(report.id), !busy);
        }
        if (!history.isEmpty()) action(content, "上一页", AppUi.SECONDARY, v -> {
            List<String> previous = new ArrayList<>(history);
            String target = previous.remove(previous.size() - 1);
            message = ""; loadPage(target, previous);
        }, !busy);
        if (page.nextCursor != null) action(content, "下一页", AppUi.SECONDARY, v -> {
            List<String> next = new ArrayList<>(history); next.add(cursor);
            message = ""; loadPage(page.nextCursor, next);
        }, !busy);
    }
    private void renderDetail() {
        action(content, busy ? "正在处理…" : "刷新查看", AppUi.SECONDARY, v -> loadDetail(selectedId), !busy);
        if (detail == null) return;
        FeedbackReport.Summary report = detail.summary;
        TextView title = text(report.title, 24); AppUi.heading(title); content.addView(title);
        content.addView(text(FeedbackText.status(report.status) + "\n提交于 " + date(report.createdAt)
                + "\n最近更新 " + date(report.updatedAt) + "\n到期时间 " + date(report.expiresAt), 14));
        for (FeedbackReport.Section section : detail.sections) section(section.label, section.text);
        for (FeedbackReport.Supplement addition : detail.supplements) section("AI 补充 · " + date(addition.createdAt), addition.text);
        section("维护回复", detail.reply.isEmpty() ? "暂时没有回复。" : detail.reply);
        content.addView(text("删除后，这份报告和回复将不再保留。", 14));
        action(content, "删除这份报告", AppUi.REVOKE, v -> delete(), !busy && !deleteUncertain);
    }
    private void section(String label, String body) {
        LinearLayout group = AppUi.group(content);
        TextView title = text(label, 16); AppUi.heading(title); group.addView(title);
        group.addView(text(body, 16));
    }
    private TextView text(String value, int size) {
        TextView view = AppUi.text(this, FeedbackText.display(value, identity == null ? null : identity.token), size);
        // String content only: no HTML, link movement method, auto-linking, or browser navigation.
        view.setAutoLinkMask(0); view.setLinksClickable(false); view.setMovementMethod(null);
        view.setSaveEnabled(false);
        return view;
    }
    private Button button(String name, int style, View.OnClickListener listener) {
        Button button = new Button(this) {
            @Override public boolean onFilterTouchEventForSecurity(android.view.MotionEvent event) {
                return (event.getFlags() & (android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED | android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) == 0
                        && super.onFilterTouchEventForSecurity(event);
            }
        };
        button.setText(name); button.setFilterTouchesWhenObscured(true); button.setOnClickListener(listener); AppUi.button(button, style);
        return button;
    }
    private void action(LinearLayout parent, String label, int style, View.OnClickListener listener, boolean enabled) {
        Button button = button(label, style, listener); button.setEnabled(enabled); parent.addView(button);
    }
    private static String date(long seconds) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(seconds * 1000)); }
}
