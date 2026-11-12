package io.airtap.preview;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Isolated presentation fixture; no Internet permission, pairing data or phone control. */
public final class ExperienceActivity extends Activity {
    Button task;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RecoveryStatus status = RecoveryStatus.valueOf(getIntent().getStringExtra("state"));
        LinearLayout root = AppUi.root(this), body = AppUi.column(this, 24);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); root.addView(scroll);
        body.addView(AppUi.text(this, "界面检查 · 合成状态", 13));
        body.addView(AppUi.deviceIdentity(this)); AppUi.space(body, 24);
        body.addView(AppUi.text(this, OwnerStatus.title(status, getIntent().getStringExtra("active")), 28));
        body.addView(AppUi.text(this, OwnerStatus.detail(status), 16));
        if (OwnerStatus.canStartTask(status)) { task = FirstTask.button(this); body.addView(task); }
        setContentView(root);
    }
}
