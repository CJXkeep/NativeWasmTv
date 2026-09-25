package xiao.bu.tv;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static boolean installed;

    private CrashReporter() {
    }

    static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                writeCrash(appContext, thread, error);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    /**
     * 崩溃前因与崩溃栈写在同一份文件里（I4 决策 2）。
     * 快照是无锁 volatile 读、不做 IO；本方法自身也绝不允许抛出。
     */
    private static void writeFailureSnapshot(PrintWriter writer) {
        try {
            String snapshot = PlaybackDiagnostics.snapshotForCrash();
            if (snapshot == null || snapshot.length() == 0) {
                return;
            }
            writer.println("--- playback failures ---");
            writer.println(snapshot);
        } catch (Throwable ignored) {
        }
    }

    private static void writeCrash(Context context, Thread thread, Throwable error) {
        FileOutputStream output = null;
        PrintWriter writer = null;
        try {
            output = new FileOutputStream(new File(context.getFilesDir(),
                    PlaybackDiagnostics.CRASH_FILE_NAME));
            writer = new PrintWriter(output);
            writer.println("time=" + System.currentTimeMillis());
            writer.println("thread=" + (thread == null ? "unknown" : thread.getName()));
            // 崩溃栈在前：读取与发送都按「头部优先」截断，异常类型与消息最有价值。
            if (error != null) {
                error.printStackTrace(writer);
            }
            writeFailureSnapshot(writer);
            writer.flush();
            output.getFD().sync();
        } catch (Throwable writeError) {
            Log.e(TAG, "Unable to persist Java crash", writeError);
        } finally {
            if (writer != null) {
                writer.close();
            } else if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
