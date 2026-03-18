package dreamdust.talking_weapon.basic_talking_weapon.studio;

import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Studio 相关异常的专用日志。
 * <p>
 * 目标：让配置界面里触发的 Studio 导出 / 发布失败，不再只混在控制台与 latest.log 中。
 */
public final class StudioErrorLogger {

    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DISPLAY_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String LATEST_FILENAME = "studio_latest_error.log";

    private StudioErrorLogger() {
    }

    public static Path log(String action, Throwable error) {
        try {
            Path dir = AgentLogger.getLogDir();
            Files.createDirectories(dir);

            String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
            Path target = dir.resolve("studio_error_" + timestamp + ".log");
            String body = buildBody(action, error);

            Files.writeString(
                    target,
                    body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Files.writeString(
                    getLatestLogPath(),
                    body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        } catch (Exception loggingError) {
            loggingError.printStackTrace();
            return null;
        }
    }

    public static Path getLatestLogPath() {
        return AgentLogger.getLogDir().resolve(LATEST_FILENAME);
    }

    private static String buildBody(String action, Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("╔══════════════════════════════════════════════════════════════╗");
        pw.println("║            TALKING WEAPON — STUDIO ERROR LOG                ║");
        pw.println("╚══════════════════════════════════════════════════════════════╝");
        pw.println();
        pw.println("时间: " + LocalDateTime.now().format(DISPLAY_TIME_FMT));
        pw.println("动作: " + (action != null ? action : "unknown"));
        pw.println();
        pw.println("[Exception]");
        if (error == null) {
            pw.println("null");
        } else {
            pw.println(error.getClass().getName() + ": " + error.getMessage());
            pw.println();
            pw.println("[Stacktrace]");
            error.printStackTrace(pw);
        }
        pw.flush();
        return sw.toString();
    }
}
