package test.visual;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class VisualActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER);
        TextView title = new TextView(this); title.setText("Synthetic visual window fixture");
        layout.addView(title);
        Button button = new Button(this); button.setText("Synthetic button");
        button.setOnClickListener(v -> title.setText("Synthetic button clicked")); layout.addView(button);
        setContentView(layout);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (getIntent().getBooleanExtra("photos", false)) layout.postDelayed(() -> requestPermissions(
                new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, 1), 400);
    }
}
