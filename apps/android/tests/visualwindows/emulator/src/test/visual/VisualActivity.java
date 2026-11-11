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
    TextView title;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER);
        title = new TextView(this); title.setText("Synthetic visual window fixture");
        layout.addView(title);
        Button button = new Button(this); button.setText("Synthetic button");
        button.setOnClickListener(v -> title.setText("Synthetic button clicked")); layout.addView(button);
        setContentView(layout);
        if (getIntent().getBooleanExtra("picker", false)) layout.postDelayed(() -> startActivityForResult(
                new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), 2), 400);
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (getIntent().getBooleanExtra("photos", false)) layout.postDelayed(() -> requestPermissions(
                new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, 1), 400);
    }
    @Override protected void onActivityResult(int request, int code, android.content.Intent data) {
        super.onActivityResult(request,code,data);
        if(request==2&&code==RESULT_OK&&data!=null&&data.getData()!=null) {
            try(java.io.InputStream input=getContentResolver().openInputStream(data.getData())) {
                byte[] bytes=input.readAllBytes();String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                title.setText("Selected SHA256 "+hash);
            }catch(Exception e){title.setText("Picker read failed");}
        }
    }
}
