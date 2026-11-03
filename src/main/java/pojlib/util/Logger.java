package pojlib.util;

import androidx.annotation.Keep;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.util.regex.Pattern;

/** Singleton class made to log on one file
 * The singleton part can be removed but will require more implementation from the end-dev
 */
@Keep
public class Logger {

    /* Instance variables */
    private final File mLogFile;
    private PrintStream mLogStream;
    private WeakReference<eventLogListener> mLogListenerWeakReference = null;

    /* No public construction */
    private Logger(){
        this("latestlog.txt");
    }

    private Logger(String fileName){
        mLogFile = new File(Constants.USER_HOME, fileName);
        // Make a new instance of the log file
        mLogFile.delete();
        try {
            mLogFile.createNewFile();
            mLogStream = new PrintStream(mLogFile.getAbsolutePath());
        }catch (IOException e){e.printStackTrace();}

    }

    private static final class SLoggerSingletonHolder {
        static final Logger sLoggerSingleton = new Logger();
    }

    public static Logger getInstance(){
        return SLoggerSingletonHolder.sLoggerSingleton;
    }


    /** Print the text to the log file if not censored */
    public void appendToLog(String text){
        if(shouldCensorLog(text)) return;
        appendToLogUnchecked(text);
    }

    /** Print the text to the log file, no china censoring there */
    public synchronized void appendToLogUnchecked(String text){
        // The native stdout bridge hands over chunks that may hold several lines
        for (String line : text.split("\n")) {
            writeLine(line);
        }
        notifyLogListener(text);
    }

    // ---- Colors for the launcher's log view (a TextMeshPro text, which renders rich text tags) ----

    private static final String RED = "#FF5555", DARK_RED = "#D07070", YELLOW = "#FFD54F", GREEN = "#7CD67C",
            GRAY = "#8C8C8C", CYAN = "#4DD0E1", MAGENTA = "#E57CE5";
    private static final Pattern KNOWN = Pattern.compile(
            "incompatible JNA native library|oshi\\.|SystemReport|Failed retrieving info for group|JNA|jna\\.|offending library|jnidispatch|^Expected: \\d|^Found:\\s+\\d|- remove or uninstall|- set the system property jna|- set jna\\.boot|jnidispatch library included|To resolve this issue");
    private static final Pattern FATAL = Pattern.compile(
            "FATAL|Fatal signal|SIGSEGV|SIGSYS|SIGABRT|Exception in thread|Caused by|UnsatisfiedLinkError|NoClassDefFoundError|ExceptionInInitializerError|OutOfMemoryError|StackOverflowError|NoSuchMethodError|NoSuchFieldError|ClassNotFoundException|/ERROR\\]|Java Exit code: -?[1-9]|Failed to install|Unable to download|Crash Report|Exception:|Exception$|Error:|Error$|failed");
    private static final Pattern WARN = Pattern.compile("/WARN\\]|WARNING|Warning:");
    private static final Pattern GOOD = Pattern.compile(
            "Loading Minecraft|Loading \\d+ mods|Backend library|OpenGL|OpenXR initialized|VR connected|Sound engine started|Created: \\d+x\\d+|Reloading ResourceManager| installed$|Java Exit code: 0|Done processing args|Setting user|Downloading|downloaded$");
    private static final Pattern STACK = Pattern.compile("^\\s+at |^\\s+\\.\\.\\. \\d+ more");
    private static final Pattern GAME_OPENED = Pattern.compile("Sound engine started|OpenXR initialized|VR connected");

    private long mLastLogTime = System.currentTimeMillis();
    private String mLastLine = null;
    private int mRepeats = 0;
    private boolean mInRenderDump = false;
    private volatile boolean mGameOpened = false;

    private static String color(String hex, String text) {
        // noparse keeps things like <clinit> from being read as tags
        return "<color=" + hex + "><noparse>" + text + "</noparse></color>";
    }

    private void writeLine(String line) {
        mLastLogTime = System.currentTimeMillis();
        if (GAME_OPENED.matcher(line).find()) {
            mGameOpened = true;
        }

        // Collapse identical consecutive lines and flag a possible loop
        if (line.equals(mLastLine)) {
            mRepeats++;
            if (mRepeats == 50) {
                writeBanner(RED, "POSSIVEL LOOP: a mesma linha se repetiu 50 vezes seguidas");
            }
            return;
        }
        if (mRepeats > 0) {
            mLogStream.println(color(MAGENTA, "    ^ linha acima repetida mais " + mRepeats + " vez(es)"));
        }
        mLastLine = line;
        mRepeats = 0;

        mLogStream.println(colorize(line));
    }

    private String colorize(String line) {
        if (line.contains("questcraft stack dump")) {
            mInRenderDump = false;
            return "<b>" + color(CYAN, ">>> DUMP DE THREADS (onde cada thread esta agora)") + "</b>";
        }
        if (line.startsWith("\"Render thread\"")) {
            mInRenderDump = true;
            return "<b>" + color(MAGENTA, ">>> Render thread (thread principal do jogo): " + line) + "</b>";
        }
        if (line.startsWith("\"")) {
            mInRenderDump = false;
        }
        boolean stackLine = STACK.matcher(line).find();
        if (mInRenderDump && stackLine) return color(MAGENTA, line);
        if (KNOWN.matcher(line).find()) return color(GRAY, line);
        if (stackLine) return color(DARK_RED, line);
        if (FATAL.matcher(line).find()) return "<b>" + color(RED, line) + "</b>";
        if (WARN.matcher(line).find()) return color(YELLOW, line);
        if (GOOD.matcher(line).find()) return color(GREEN, line);
        return "<noparse>" + line + "</noparse>";
    }

    /** A highlighted banner, e.g. for a detected loop or hang */
    public synchronized void writeBanner(String hex, String text) {
        mLogStream.println("<b>" + color(hex, "==================== " + text + " ====================") + "</b>");
    }

    /** A status line from GameMonitor; does not count as game output */
    public synchronized void writeStatus(String text) {
        mLogStream.println(color(GRAY, "[monitor] " + text));
    }

    public long getLastLogTime() {
        return mLastLogTime;
    }

    public boolean isGameOpened() {
        return mGameOpened;
    }

    public static final String BANNER_RED = RED;
    public static final String BANNER_YELLOW = YELLOW;

    /** Reset the log file, effectively erasing any previous logs */
    public void reset(){
        try{
            mLogFile.delete();
            mLogFile.createNewFile();
            mLogStream = new PrintStream(mLogFile.getAbsolutePath());
        }catch (IOException e){ e.printStackTrace();}
    }

    /** Disables the printing */
    public void shutdown(){
        mLogStream.close();
    }

    /**
     * Perform various checks to see if the log is safe to print
     * Subclasses may want to override this behavior
     * @param text The text to check
     * @return Whether the log should be censored
     */
    private static boolean shouldCensorLog(String text){
        return text.contains("Session ID is");
    }

    /** Small listener for anything listening to the log */
    public interface eventLogListener {
        void onEventLogged(String text);
    }

    /** Link a log listener to the logger */
    public void setLogListener(eventLogListener logListener){
        this.mLogListenerWeakReference = new WeakReference<>(logListener);
    }

    /** Notifies the event listener, if it exists */
    private void notifyLogListener(String text){
        if(mLogListenerWeakReference == null) return;
        eventLogListener logListener = mLogListenerWeakReference.get();
        if(logListener == null){
            mLogListenerWeakReference = null;
            return;
        }
        logListener.onEventLogged(text);
    }
}
