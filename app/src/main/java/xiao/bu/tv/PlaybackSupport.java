package xiao.bu.tv;

import android.util.Log;
import com.bu.cc.tv.NativeCmgDecryptor;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class PlaybackSupport {
    private static final String TAG = "PlaybackSupport";
    private PlaybackSupport() {
    }
    static int initializeCmgAtOfficialInitTag(long initTimeMs, int targetInitTag) {
        int bestOffset = 0;
        int bestResult = 0;
        int[] offsets = new int[121];
        offsets[0] = 0;
        int count = 1;
        for (int offset = 1; offset <= 60; offset++) {
            offsets[count++] = offset;
            offsets[count++] = -offset;
        }
        for (int index = 0; index < count; index++) {
            int offset = offsets[index];
            NativeCmgDecryptor.resetRuntimeForProbe();
            NativeCmgDecryptor.setClockForProbe(initTimeMs + offset);
            if (!NativeCmgDecryptor.initializeRuntimeForProbe()) {
                continue;
            }
            int result = NativeCmgDecryptor.getPlayerInitResultForProbe();
            if (index == 0) {
                bestResult = result;
            }
            if (targetInitTag != 0 && result == targetInitTag) {
                Log.i(TAG, "CMG native InitPlayer matched official tag="
                        + String.format(Locale.US, "%08x", targetInitTag)
                        + " offsetMs=" + offset);
                return offset;
            }
            bestOffset = offset;
        }
        NativeCmgDecryptor.resetRuntimeForProbe();
        NativeCmgDecryptor.setClockForProbe(initTimeMs);
        NativeCmgDecryptor.initializeRuntimeForProbe();
        Log.w(TAG, "CMG native InitPlayer did not match official tag target="
                + String.format(Locale.US, "%08x", targetInitTag)
                + " first=" + String.format(Locale.US, "%08x", bestResult)
                + " searchedOffsetMs=" + bestOffset);
        return 0;
    }

    static void waitForCmgUpdateTag(int currentTag, int targetTag) {
        if (targetTag == 0 || currentTag == targetTag) {
            return;
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 1500L;
        int lastTag = currentTag;
        int attempts = 0;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempts++;
            lastTag = NativeCmgDecryptor.updateSessionForProbe();
            if (lastTag == targetTag) {
                Log.i(TAG, "CMG native reached official updateTag="
                        + String.format(Locale.US, "%08x", targetTag)
                        + " attempts=" + attempts);
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.w(TAG, "CMG native did not reach official updateTag target="
                + String.format(Locale.US, "%08x", targetTag)
                + " last=" + String.format(Locale.US, "%08x", lastTag)
                + " attempts=" + attempts);
    }

    static void resetPlaybackLayerImmediately(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setAlpha(1f);
    }

    static byte[] readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static boolean isSoftwareCodecName(String codecName) {
        String lower = codecName.toLowerCase(Locale.US);
        return lower.startsWith("omx.google.")
                || lower.startsWith("omx.pv.")
                || lower.startsWith("omx.ffmpeg.")
                || lower.startsWith("omx.avcodec.")
                || lower.startsWith("c2.android.")
                || lower.contains(".software.")
                || lower.contains(".sw.");
    }
}
