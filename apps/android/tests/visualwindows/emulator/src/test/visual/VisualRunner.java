package test.visual;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** The test oracle can inspect native UI; every tested action goes through production CompanionUi. */
public final class VisualRunner extends Instrumentation {
    static final String FIXTURE = "io.airtap.fixture.visual", OWNER = "a".repeat(32);
    final JSONArray checks = new JSONArray();
    final AtomicBoolean allowed = new AtomicBoolean(true), current = new AtomicBoolean(true);
    Object ui, service; Class<?> replyClass; Method execute;
    JSONObject dialogMetadata;
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    void check(String name, boolean okay) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", okay));
        if (!okay) throw new AssertionError(name);
    }
    JSONObject q(String action) throws Exception { return new JSONObject().put("id", UUID.randomUUID().toString()).put("action", action); }
    JSONObject request(JSONObject command) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Object reply = Proxy.newProxyInstance(replyClass.getClassLoader(), new Class[]{replyClass}, (p,m,a) -> {
            value.set(m.getName().equals("success") ? (JSONObject)a[0] : new JSONObject().put("error", a[0]));
            done.countDown(); return null;
        });
        runOnMainSync(() -> { try { execute.invoke(ui, command, reply, (BooleanSupplier)current::get, OWNER, (BooleanSupplier)allowed::get); }
                             catch (Throwable e) { error.set(e); done.countDown(); } });
        if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("command timeout");
        if (error.get() != null) throw new RuntimeException(error.get());
        return value.get();
    }
    void launch(String mode) throws Exception {
        getContext().startActivity(new Intent().setClassName(FIXTURE, "test.visual.VisualActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(mode, true));
        Thread.sleep(1200);
    }
    AccessibilityNodeInfo oracle() { return getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow(); }
    AccessibilityNodeInfo findOnce(String... terms) {
        AccessibilityNodeInfo root = oracle(); if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> todo = new ArrayDeque<>(); todo.add(root);
        while (!todo.isEmpty()) {
            AccessibilityNodeInfo node = todo.remove();
            for (String term : terms) if (term.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())
                    || node.getViewIdResourceName() != null && node.getViewIdResourceName().endsWith(":id/" + term)) return node;
            for (int i=0; i<node.getChildCount(); i++) { var child=node.getChild(i); if(child != null) todo.add(child); }
        }
        return null;
    }
    AccessibilityNodeInfo find(String... terms) throws Exception {
        for (int i=0;i<15;i++) { AccessibilityNodeInfo found=findOnce(terms); if(found!=null)return found; Thread.sleep(200); }
        return null;
    }
    JSONObject image() throws Exception { Thread.sleep(400); return request(q("observe")); }
    JSONObject tap(JSONObject image, double x, double y) throws Exception {
        JSONObject command=q("tap").put("observation_id",image.getString("observation_id")).put("x",x).put("y",y);
        if (!image.isNull("package")) command.put("package",image.getString("package"));
        return request(command);
    }
    JSONObject tapNode(JSONObject image, AccessibilityNodeInfo node) throws Exception {
        if (node == null) throw new AssertionError("oracle target missing: " + oracle());
        Rect r = new Rect(); node.getBoundsInScreen(r);
        JSONArray screen=image.getJSONArray("screen_bounds");
        return tap(image,r.exactCenterX()/screen.getInt(2),r.exactCenterY()/screen.getInt(3));
    }
    JSONObject metadata(JSONObject image) throws Exception {
        JSONObject safe=new JSONObject(image.toString()); safe.remove("image_base64"); return safe;
    }
    void tests() throws Exception {
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        launch("normal"); JSONObject image=image();
        check("native normal app screenshot",image.has("observation_id") && image.getString("package").equals(FIXTURE));
        tapNode(image,find("Synthetic button")); Thread.sleep(300);
        check("normal app still receives production visual tap",find("Synthetic button clicked") != null);
        launch("photos"); image=image(); dialogMetadata=metadata(image);
        check("real Android media permission dialog captured",image.has("observation_id") && oracle()!=null && String.valueOf(oracle().getPackageName()).contains("permissioncontroller"));
        byte[] pixels=android.util.Base64.decode(image.getString("image_base64"),android.util.Base64.DEFAULT);
        check("native dialog screenshot contains actual image bytes",pixels.length>1000 && pixels[0]==(byte)255 && pixels[1]==(byte)216);
        java.nio.file.Files.write(new java.io.File(getTargetContext().getFilesDir(),"visual-fixture-dialog.jpg").toPath(),pixels);
        if (image.isNull("package")) {
            check("rootless image advertises actual window without underlying app",!image.getBoolean("root_available") && image.getString("target_mode").equals("visual_window_v1"));
            JSONObject tree=request(q("read_tree")); check("tree cannot substitute underlying fixture",tree.optString("error").equals("window_root_unavailable"));
            Method recovery=ui.getClass().getDeclaredMethod("hasRecoveryWindow");recovery.setAccessible(true);
            check("rootless dialog is not mistaken for wake recovery window",Boolean.FALSE.equals(recovery.invoke(ui)));
            // A failed tree invalidates observations; acquire the real dialog again.
            image=image();
            JSONObject denied=tap(image,.001,.001);
            check("rootless coordinates outside actual window rejected",denied.optString("error").equals("outside_observed_window"));
            image=image();
        }
        allowed.set(false); JSONObject denied=tap(image,.5,.7);
        check("revocation prevents visual tap",denied.optString("error").equals("permission_denied")); allowed.set(true);
        image=image(); JSONObject before=image;
        JSONObject tapped=tapNode(image,find("permission_deny_button","Don't allow","Deny")); Thread.sleep(500);
        dialogMetadata.put("tap_result",tapped).put("after_windows",request(q("windows"))).put("after_oracle_package",oracle()==null?JSONObject.NULL:oracle().getPackageName());
        boolean dismissed=oracle()!=null && FIXTURE.contentEquals(oracle().getPackageName());
        dialogMetadata.put("tap_dismissed",dismissed).put("android_sdk",android.os.Build.VERSION.SDK_INT);
        if (android.os.Build.VERSION.SDK_INT >= 36 && !dismissed) {
            check("Android16 protected media dialog remains unchanged after injected touch",oracle()!=null && String.valueOf(oracle().getPackageName()).contains("permissioncontroller"));
            JSONObject escape=request(q("back"));Thread.sleep(500);
            check("Back recovers from platform-blocked dialog touch",escape.optBoolean("accepted") && oracle()!=null && FIXTURE.contentEquals(oracle().getPackageName()));
        } else check("production visual tap dismisses real media permission dialog",dismissed);
        check("dialog action reports accepted or unknown requiring observation",tapped.optBoolean("accepted") || tapped.optString("error").contains("outcome_unknown"));
        denied=tap(before,.5,.7); check("old dialog image cannot target resumed app",denied.optString("error").equals("fresh_observation_required"));
        launch("photos"); Thread.sleep(200);
        JSONObject back=request(q("back")); Thread.sleep(500);
        check("Back leaves real system permission dialog without a UI tree",back.optBoolean("accepted") && oracle()!=null && FIXTURE.contentEquals(oracle().getPackageName()));
        launch("secure"); denied=image();
        // Android may refuse capture or blank this FLAG_SECURE window. Never treat it as a readable private surface.
        boolean protectedPixels=denied.has("error");
        if (!protectedPixels) {
            byte[] bytes=android.util.Base64.decode(denied.getString("image_base64"),android.util.Base64.DEFAULT);
            android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            int dark=0,total=0;
            for(int y=bitmap.getHeight()/3;y<bitmap.getHeight()*2/3;y+=4)for(int x=bitmap.getWidth()/5;x<bitmap.getWidth()*4/5;x+=4){int pixel=bitmap.getPixel(x,y);total++;if(android.graphics.Color.red(pixel)<8&&android.graphics.Color.green(pixel)<8&&android.graphics.Color.blue(pixel)<8)dark++;}
            protectedPixels=dark>total*.99;bitmap.recycle();
        }
        check("Android secure window content remains unavailable",protectedPixels);
        launch("normal"); image=image(); current.set(false); denied=tap(image,.5,.5);
        check("stopped command prevents visual dispatch",denied.optString("error").equals("permission_denied")); current.set(true);
        request(q("home"));
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Class<?> preview=Class.forName("io.airtap.preview.PreviewService"); Field f=preview.getDeclaredField("instance");f.setAccessible(true);
            for(int i=0;i<150 && service==null;i++){service=f.get(null);if(service==null)Thread.sleep(200);}
            if(service==null)throw new AssertionError("no accessibility service");
            Class<?> type=Class.forName("io.airtap.preview.CompanionUi");Constructor<?> c=type.getDeclaredConstructors()[0];c.setAccessible(true);ui=c.newInstance(service,(BooleanSupplier)allowed::get);
            replyClass=Class.forName("io.airtap.preview.CompanionUi$Reply");execute=type.getDeclaredMethod("execute",JSONObject.class,replyClass,BooleanSupplier.class,String.class,BooleanSupplier.class);execute.setAccessible(true);
            tests();
            result.putString("report",new JSONObject().put("complete",true).put("checks",checks).put("dialog",dialogMetadata).toString());finish(-1,result);
        } catch(Throwable e) {
            try { result.putString("report",new JSONObject().put("complete",false).put("checks",checks).put("dialog",dialogMetadata).toString()); } catch(JSONException ignored) {}
            result.putString("error",android.util.Log.getStackTraceString(e));finish(1,result);
        }
    }
}
