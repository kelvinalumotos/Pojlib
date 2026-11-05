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
    // latestlog.txt is what the launcher shows: only the loading progress, hang/loop warnings and errors.
    // Everything else goes to latestlog-completo.txt, for debugging.
    private final File mLogFile;
    private final File mFullLogFile;
    private PrintStream mLogStream;
    private PrintStream mFullStream;
    private WeakReference<eventLogListener> mLogListenerWeakReference = null;

    /* No public construction */
    private Logger(){
        this("latestlog.txt");
    }

    private Logger(String fileName){
        mLogFile = new File(Constants.USER_HOME, fileName);
        mFullLogFile = new File(Constants.USER_HOME, fileName.replace(".txt", "-completo.txt"));
        openFiles();
    }

    private void openFiles() {
        try {
            mLogFile.delete();
            mLogFile.createNewFile();
            mLogStream = new PrintStream(mLogFile.getAbsolutePath());
            mFullLogFile.delete();
            mFullLogFile.createNewFile();
            mFullStream = new PrintStream(mFullLogFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        mProgress = 0;
        mErrorShown = false;
        mRecentErrors.clear();
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
            if (mRepeats == 50 && !mGameOpened) {
                writeBanner(RED, "POSSIVEL LOOP: a mesma linha se repetiu 50 vezes seguidas");
            }
            return;
        }
        if (mRepeats > 0) {
            mFullStream.println(color(MAGENTA, "    ^ linha acima repetida mais " + mRepeats + " vez(es)"));
        }
        mLastLine = line;
        mRepeats = 0;

        mFullStream.println(colorize(line));
        rememberError(line);
        updateProgress(line);
        showUserError(line);
    }

    // ---- Errors in the launcher view: only those that stop the game, with the lines that led to them ----

    private static final Pattern USER_ERROR = Pattern.compile(
            "Game crashed|Java Exit code: -?[1-9]|Crash report saved|Exception in thread \"(main|Render thread)\"|Fatal signal|SIGSEGV|SIGABRT|SIGSYS|OutOfMemoryError|Failed to install|Unable to download|Mods failed to download|Could not download");
    private final java.util.ArrayDeque<String> mRecentErrors = new java.util.ArrayDeque<>();
    private boolean mErrorShown = false;

    /** keeps the last few error lines (not the known harmless ones) to explain a crash */
    private void rememberError(String line) {
        if (KNOWN.matcher(line).find()) {
            return;
        }
        if (FATAL.matcher(line).find() || (STACK.matcher(line).find() && !mRecentErrors.isEmpty())) {
            mRecentErrors.addLast(line);
            while (mRecentErrors.size() > 12) {
                mRecentErrors.removeFirst();
            }
        }
    }

    private void showUserError(String line) {
        if (!USER_ERROR.matcher(line).find()) {
            return;
        }
        if (!mErrorShown) {
            mErrorShown = true;
            mLogStream.println();
            mLogStream.println("<b>" + color(RED, "ERRO: " + line) + "</b>");
            if (!mRecentErrors.isEmpty()) {
                mLogStream.println(color(GRAY, "Ultimas mensagens de erro antes disso:"));
                for (String e : mRecentErrors) {
                    mLogStream.println(color(DARK_RED, e));
                }
            }
            mLogStream.println(color(GRAY, "Log completo em latestlog-completo.txt"));
        } else {
            mLogStream.println(color(RED, line));
        }
        mLogStream.flush();
    }

    // ---- Startup progress, from the milestones that show up in the log ----

    private static final Object[][] MILESTONES = {
            {Pattern.compile("^Checking LWJGL"), 5, "Preparando LWJGL"},
            {Pattern.compile("^Checking JRE"), 10, "Verificando o Java"},
            {Pattern.compile("^Installing JRE"), 12, "Baixando o Java"},
            {Pattern.compile("^Checking Client"), 18, "Verificando o Minecraft"},
            {Pattern.compile("^Checking Libraries"), 24, "Verificando bibliotecas"},
            {Pattern.compile("^Checking assets"), 30, "Verificando assets"},
            {Pattern.compile("^Modifying Vivecraft config"), 34, "Configurando o Vivecraft"},
            {Pattern.compile("^QuestCraft: Setting JVM memory"), 38, "Iniciando o Java"},
            {Pattern.compile("Done processing args"), 42, "Java iniciado"},
            {Pattern.compile("Loading Minecraft .* with Fabric"), 48, "Minecraft carregando (Fabric)"},
            {Pattern.compile("Loading \\d+ mods"), 52, "Carregando mods"},
            {Pattern.compile("Vivecraft: applying"), 56, "Aplicando ajustes do Vivecraft"},
            {Pattern.compile("Setting user"), 62, "Conta conectada"},
            {Pattern.compile("Backend library"), 68, "Iniciando janela e OpenGL"},
            {Pattern.compile("Backend API|OpenGL|GL version"), 74, "OpenGL pronto"},
            {Pattern.compile("Reloading ResourceManager"), 82, "Carregando recursos"},
            {Pattern.compile("Created: \\d+x\\d+"), 90, "Montando texturas"},
            {Pattern.compile("Sound engine started|OpenXR initialized|VR connected"), 100, "Minecraft iniciado!"},
    };
    private static final Pattern PROGRESS_FAILED = Pattern.compile("Game crashed|Java Exit code: -?[1-9]|Crash report saved");
    private int mProgress = 0;

    private void updateProgress(String line) {
        // A new launch starts over
        if (((Pattern) MILESTONES[0][0]).matcher(line).find()) {
            mProgress = 0;
            mErrorShown = false;
            mRecentErrors.clear();
        }
        if (mProgress > 0 && mProgress < 100 && PROGRESS_FAILED.matcher(line).find()) {
            writeBoth("<b>" + color(RED, progressBar(mProgress) + " PAROU EM " + mProgress + "% (o jogo fechou com erro)") + "</b>");
            mProgress = 0;
            return;
        }
        for (Object[] m : MILESTONES) {
            int percent = (Integer) m[1];
            if (percent > mProgress && ((Pattern) m[0]).matcher(line).find()) {
                mProgress = percent;
                mStepName = (String) m[2];
                mStepSince = System.currentTimeMillis();
                String hex = percent == 100 ? GREEN : CYAN;
                writeBoth("<b>" + color(hex, progressBar(percent) + " " + percent + "% " + m[2]) + "</b>");
                return;
            }
        }
    }

    private String mStepName = "Iniciando";
    private long mStepSince = System.currentTimeMillis();

    /** the current loading step and how long it has been going, for "still loading" messages */
    public synchronized String describeStep() {
        long seconds = (System.currentTimeMillis() - mStepSince) / 1000;
        return mProgress + "% " + mStepName + " (" + seconds + "s nesta etapa)";
    }

    public int getProgress() {
        return mProgress;
    }

    private void writeBoth(String text) {
        mLogStream.println(text);
        mLogStream.flush();
        mFullStream.println(text);
    }

    private static String progressBar(int percent) {
        int filled = percent / 5;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            sb.append(i < filled ? '#' : '-');
        }
        return sb.append(']').toString();
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

    /** A highlighted banner, e.g. for a detected loop or hang, shown in the launcher too */
    public synchronized void writeBanner(String hex, String text) {
        writeBoth("<b>" + color(hex, "==================== " + text + " ====================") + "</b>");
    }

    /** A status line from GameMonitor; does not count as game output, only in the full log */
    public synchronized void writeStatus(String text) {
        mFullStream.println(color(GRAY, "[monitor] " + text));
    }

    /** A short "still loading" line for the launcher view */
    public synchronized void writeUserStatus(String text) {
        writeBoth(color(GRAY, text));
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
    public synchronized void reset(){
        mLogStream.close();
        mFullStream.close();
        openFiles();
    }

    /** Disables the printing */
    public void shutdown(){
        mLogStream.close();
        mFullStream.close();
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
