package pojlib.util;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import pojlib.API;

/**
 * Watches the game's render thread while the game starts, and writes its CPU use, plus loop and hang
 * warnings, to the launcher log. The game JVM runs inside this process, so its threads are readable
 * from /proc/self/task without root. Stops once the game has opened.
 */
public class GameMonitor {
    private static final String RENDER_THREAD = "Render thread";
    private static final long PERIOD_MS = 5000;
    private static final long STATUS_EVERY_MS = 10000;
    private static final long ALERT_EVERY_MS = 30000;

    private static Thread sThread;

    public static synchronized void start() {
        if (sThread != null && sThread.isAlive()) {
            return;
        }
        sThread = new Thread(GameMonitor::run, "QuestCraft game monitor");
        sThread.setDaemon(true);
        sThread.start();
    }

    private static void run() {
        long clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK);
        if (clockTicks <= 0) {
            clockTicks = 100;
        }
        Logger log = Logger.getInstance();
        int tid = -1;
        long prevTicks = -1, prevTime = 0, lastStatus = 0, lastAlert = 0;

        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (API.gameReady || log.isGameOpened()) {
                log.writeStatus("jogo aberto, monitor desligado");
                return;
            }

            if (tid < 0 || readCpuTicks(tid) < 0) {
                tid = findThread(RENDER_THREAD);
                prevTicks = -1;
                if (tid < 0) {
                    continue;
                }
            }
            long ticks = readCpuTicks(tid);
            long now = SystemClock.elapsedRealtime();
            if (prevTicks >= 0 && ticks >= prevTicks) {
                double seconds = (now - prevTime) / 1000.0;
                int cpu = (int) Math.round(100.0 * (ticks - prevTicks) / clockTicks / seconds);
                long sinceLog = (System.currentTimeMillis() - log.getLastLogTime()) / 1000;

                if (now - lastStatus >= STATUS_EVERY_MS) {
                    log.writeStatus("Render thread: " + cpu + "% de CPU | ultimo log ha " + sinceLog + "s");
                    lastStatus = now;
                }
                if (now - lastAlert >= ALERT_EVERY_MS) {
                    if (cpu >= 80 && sinceLog >= 20) {
                        log.writeBanner(Logger.BANNER_RED,
                                "POSSIVEL LOOP: Render thread a " + cpu + "% de CPU sem log novo ha " + sinceLog + "s");
                        lastAlert = now;
                    } else if (cpu <= 5 && sinceLog >= 45) {
                        log.writeBanner(Logger.BANNER_YELLOW,
                                "POSSIVEL TRAVAMENTO: Render thread parada (" + cpu + "% de CPU) sem log ha " + sinceLog + "s");
                        lastAlert = now;
                    }
                }
            }
            prevTicks = ticks;
            prevTime = now;
        }
    }

    private static int findThread(String name) {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return -1;
        }
        for (File task : tasks) {
            try {
                String comm = new String(Files.readAllBytes(new File(task, "comm").toPath())).trim();
                if (comm.equals(name)) {
                    return Integer.parseInt(task.getName());
                }
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** utime + stime of a thread in clock ticks, -1 if it is gone */
    private static long readCpuTicks(int tid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/self/task/" + tid + "/stat").toPath()));
            // Fields after the ")" closing the thread name: state is field 3, utime 14, stime 15
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
