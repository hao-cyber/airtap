package io.airtap.preview;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Presentation only: no grant, connection, credential or device-control behavior. */
final class AppUi {
    static final int BACKGROUND = 0xffffffff;
    static final int SURFACE = 0xffffffff;
    static final int INK = 0xff202124;
    static final int MUTED = 0xff64686c;
    static final int ACCENT = 0xff242628;
    static final int TINT = 0xfff4f5f5;
    static final int LINE = 0xffebeced;
    static final int DANGER = 0xffa12d31;
    static final int PRIMARY = 0;
    static final int SECONDARY = 1;
    static final int QUIET = 2;
    static final int STOP = 3;
    static final int REVOKE = 4;

    private AppUi() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context, int padding) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int p = dp(context, padding);
        view.setPadding(p, p, p, p);
        return view;
    }

    static LinearLayout root(Activity activity) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        LinearLayout root = column(activity, 0);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
            return insets;
        });
        return root;
    }

    static TextView text(Context context, String value, int size) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(size <= 14 ? MUTED : INK);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 2), 1.0f);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    static void heading(TextView view) {
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAccessibilityHeading(true);
    }

    static void divider(LinearLayout parent) {
        View line = new View(parent.getContext());
        line.setBackgroundColor(LINE);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1)));
    }

    static LinearLayout group(LinearLayout parent) {
        Context context = parent.getContext();
        divider(parent);
        LinearLayout group = column(context, 0);
        group.setPadding(0, dp(context, 12), 0, dp(context, 14));
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    static LinearLayout section(LinearLayout parent, String title, String detail) {
        LinearLayout group = group(parent);
        TextView heading = text(parent.getContext(), title, 16);
        heading(heading);
        group.addView(heading);
        if (!detail.isEmpty()) group.addView(text(parent.getContext(), detail, 13));
        return group;
    }

    static void inset(TextView view, boolean accent) {
        // A small inset is reserved for the verification code; ordinary status stays plain.
        int p = dp(view.getContext(), 10);
        view.setPadding(p, p, p, p);
        view.setBackground(shape(view.getContext(), TINT, 0, 3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(view.getContext(), 6);
        params.bottomMargin = dp(view.getContext(), 6);
        view.setLayoutParams(params);
    }

    static void button(Button button, int style) {
        Context context = button.getContext();
        boolean solid = style == PRIMARY;
        int color = style == STOP || style == REVOKE ? DANGER : ACCENT;
        int fill = solid ? color : style == QUIET || style == STOP || style == REVOKE ? 0x00000000 : SURFACE;
        int border = style == QUIET || style == STOP || style == REVOKE || solid ? 0 : LINE;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(context, style == QUIET ? 0x00000000 : 0xfff1f2f3, 0, 9));
        states.addState(new int[]{}, shape(context, fill, border, 9));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(solid ? 0x30ffffff : 0x18242628), states, null).mutate());
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xff969b9a, solid ? SURFACE : style == QUIET ? MUTED : color}));
        button.setTextSize(style == QUIET ? 14 : 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, 6);
        button.setLayoutParams(params);
    }

    static void checkBox(CheckBox view) {
        view.setTextSize(15);
        view.setTextColor(INK);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setPadding(0, dp(view.getContext(), 6), 0, dp(view.getContext(), 6));
        view.setButtonTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xffa7b5ac, ACCENT}));
    }

    static void input(EditText view) {
        view.setTextSize(15);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
    }

    static void row(LinearLayout parent, Button first, Button second) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1);
        a.rightMargin = dp(parent.getContext(), 5);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1);
        b.leftMargin = a.rightMargin;
        row.addView(first, a);
        row.addView(second, b);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), 8);
        parent.addView(row, params);
    }

    static LinearLayout stopBar(LinearLayout parent, Button stop, Button revoke) {
        Context context = parent.getContext();
        LinearLayout bar = column(context, 0);
        divider(bar);
        bar.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 2));
        bar.setBackgroundColor(SURFACE);
        button(stop, STOP);
        button(revoke, REVOKE);
        row(bar, stop, revoke);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return bar;
    }

    static void space(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }

    static LinearLayout header(Activity activity, TextView title, Button action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 24), 0, dp(activity, 12), 0);
        row.setMinimumHeight(dp(activity, 56));
        title.setTextSize(16);
        heading(title);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        button(action, QUIET);
        row.addView(action, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    static LinearLayout deviceIdentity(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View glyph = new View(context) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                float density = getResources().getDisplayMetrics().density;
                canvas.save();
                canvas.scale(density, density);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f);
                paint.setColor(MUTED);
                canvas.drawRoundRect(12, 8, 26, 30, 2.5f, 2.5f, paint);
                canvas.drawLine(17, 11, 21, 11, paint);
                canvas.drawLine(18, 27, 20, 27, paint);
                canvas.restore();
            }
        };
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glyph.setBackground(shape(context, TINT, 0, 8));
        row.addView(glyph, new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)));
        LinearLayout labels = column(context, 0);
        TextView model = text(context, android.os.Build.MODEL, 14);
        model.setTextColor(INK);
        heading(model);
        labels.addView(model);
        labels.addView(text(context, "Android " + android.os.Build.VERSION.RELEASE + " · 这台手机", 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(context, 12);
        row.addView(labels, params);
        return row;
    }

    static TextView statusRow(LinearLayout parent, String label) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 36));
        TextView name = text(context, label, 13);
        name.setTextColor(INK);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(context, "", 13);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    static String appNames(Context context, java.util.Collection<String> packages) {
        if (packages.isEmpty()) return "仅 Airtap 会话页；未开放其他 App。";
        StringBuilder value = new StringBuilder();
        for (String name : packages) {
            if (value.length() > 0) value.append("、");
            value.append(appLabel(context, name));
        }
        return value.toString();
    }

    static String appScope(Context context, java.util.Collection<String> packages) {
        if (packages.isEmpty()) return "仅 Airtap 会话页；未开放其他 App。";
        StringBuilder value = new StringBuilder();
        for (String name : packages) {
            if (value.length() > 0) value.append("\n\n");
            value.append(appLabel(context, name)).append("\n").append(name);
        }
        return value.toString();
    }

    static String statusHeadline(String details) {
        int line = details.indexOf('\n');
        String headline = line < 0 ? details : details.substring(0, line);
        return "已连接（手机主动 WSS）".equals(headline) ? "连接成功" : headline;
    }

    static String appLabel(Context context, String packageName) {
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static GradientDrawable shape(Context context, int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radius));
        if (border != 0) drawable.setStroke(dp(context, 1), border);
        return drawable;
    }
}
