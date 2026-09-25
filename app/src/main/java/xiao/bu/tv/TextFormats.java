package xiao.bu.tv;

import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class TextFormats {
    private static final String TAG = "TextFormats";
    private TextFormats() {
    }
    static String emptyToNull(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() == 0 ? null : normalized;
    }

    static long parsePositiveLong(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        try {
            long value = Long.parseLong(text);
            return Math.max(0L, value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    static int parseHexUpdateTag(String text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        try {
            return (int) Long.parseLong(text, 16);
        } catch (NumberFormatException error) {
            Log.w(TAG, "Invalid CMG update tag: " + text);
            return 0;
        }
    }

    static int measuredTextWidth(TextView view) {
        if (view == null || view.getVisibility() == View.GONE || view.getText() == null) {
            return 0;
        }
        String[] lines = view.getText().toString().split("\\n", -1);
        float maximum = 0f;
        for (String line : lines) {
            maximum = Math.max(maximum, view.getPaint().measureText(line));
        }
        return (int) Math.ceil(maximum);
    }

    static boolean hasSourceLinePosition(String value) {
        if (value == null) {
            return false;
        }
        int lineStart = value.indexOf("线路");
        while (lineStart >= 0) {
            int cursor = lineStart + 2;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            int firstDigit = cursor;
            while (cursor < value.length() && Character.isDigit(value.charAt(cursor))) {
                cursor++;
            }
            if (cursor > firstDigit) {
                while (cursor < value.length()
                        && Character.isWhitespace(value.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < value.length() && value.charAt(cursor) == '/') {
                    cursor++;
                    while (cursor < value.length()
                            && Character.isWhitespace(value.charAt(cursor))) {
                        cursor++;
                    }
                    int secondDigit = cursor;
                    while (cursor < value.length()
                            && Character.isDigit(value.charAt(cursor))) {
                        cursor++;
                    }
                    if (cursor > secondDigit) {
                        return true;
                    }
                }
            }
            lineStart = value.indexOf("线路", lineStart + 2);
        }
        return false;
    }

    static String formatNetworkSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 0L) {
            return "--";
        }
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB/s",
                    bytesPerSecond / (1024f * 1024f));
        }
        return Math.round(bytesPerSecond / 1024f) + " KB/s";
    }

    static long smoothBitrate(long previous, long sample) {
        if (previous <= 0L) {
            return sample;
        }
        // A 25% moving update keeps the overlay readable while following changes.
        return previous + (sample - previous) / 4L;
    }

    static String readableCodec(String mimeOrCodec, String codecs) {
        String value = mimeOrCodec;
        if (value == null || value.trim().length() == 0) {
            value = codecs;
        }
        if (value == null || value.trim().length() == 0) {
            return "--";
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("avc") || lower.contains("h264") || lower.contains("h.264")) {
            return "H.264";
        }
        if (lower.contains("hevc") || lower.contains("h265") || lower.contains("h.265")
                || lower.contains("hvc1") || lower.contains("hev1")) {
            return "H.265";
        }
        if (lower.contains("mpeg2video") || lower.contains("video/mpeg2")) {
            return "MPEG-2";
        }
        if (lower.contains("mp4v") || lower.contains("mpeg4")) {
            return "MPEG-4";
        }
        if (lower.contains("mp4a") || lower.contains("aac")) {
            return "AAC";
        }
        if (lower.contains("eac3") || lower.contains("e-ac-3")) {
            return "E-AC-3";
        }
        if (lower.contains("ac3") || lower.contains("ac-3")) {
            return "AC-3";
        }
        if (lower.contains("opus")) {
            return "Opus";
        }
        if (lower.contains("vorbis")) {
            return "Vorbis";
        }
        if (lower.contains("audio/mpeg") || lower.equals("mp3")) {
            return "MP3";
        }
        int slash = value.lastIndexOf('/');
        String shortName = slash >= 0 ? value.substring(slash + 1) : value;
        int comma = shortName.indexOf(',');
        if (comma > 0) {
            shortName = shortName.substring(0, comma);
        }
        return shortName.length() > 16 ? shortName.substring(0, 16) : shortName;
    }

    static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0L) {
            return "--";
        }
        if (bitsPerSecond >= 1000000L) {
            return String.format(Locale.US, "%.1fMbps", bitsPerSecond / 1000000f);
        }
        if (bitsPerSecond >= 1000L) {
            return Math.round(bitsPerSecond / 1000f) + "kbps";
        }
        return bitsPerSecond + "bps";
    }
}
