package xiao.bu.tv;

import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class UiMetrics {
    private static final String TAG = "UiMetrics";
    private UiMetrics() {
    }
    static float roundUiScale(float value) {
        return Math.round(value * 100f) / 100f;
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static void setExactWidth(View view, int width) {
        if (view == null) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).weight = 0f;
            }
            view.setLayoutParams(params);
        }
    }

    static float dampedGestureDistance(float distance, float viewport) {
        float absolute = Math.abs(distance);
        float damped = absolute * 0.78f / (1f + absolute / (viewport * 1.35f));
        return Math.copySign(Math.min(viewport * 0.82f, damped), distance);
    }

    static boolean isPointInsideView(MotionEvent event, View view) {
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }
}
