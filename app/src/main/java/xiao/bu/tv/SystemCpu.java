package xiao.bu.tv;

import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class SystemCpu {
    private static final String TAG = "SystemCpu";
    private SystemCpu() {
    }
    static boolean isCpuDirectoryName(String name) {
        if (name == null || name.length() <= 3 || !name.startsWith("cpu")) {
            return false;
        }
        for (int index = 3; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean isCpuOnline(File cpuDirectory) {
        File online = new File(cpuDirectory, "online");
        if (!online.exists()) {
            return true;
        }
        try {
            return !"0".equals(readSmallAsciiFile(online));
        } catch (IOException ignored) {
            return true;
        } catch (SecurityException ignored) {
            return true;
        }
    }

    static float clampCpuUsage(float usage) {
        return Math.max(0f, Math.min(100f, usage));
    }

    static String formatCpuUsage(float usage) {
        return usage >= 0f ? String.format(Locale.US, "%.0f%%", usage) : "--";
    }


    static String readSmallAsciiFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[128];
            int length = input.read(buffer);
            if (length <= 0) {
                throw new IOException("Empty file: " + file);
            }
            return new String(buffer, 0, length, "US-ASCII").trim();
        } finally {
            input.close();
        }
    }
}
