package dreamdust.talking_weapon.basic_talking_weapon.studio;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Studio workspace 作为 authored data 真源时的统一路径助手。
 * <p>
 * authored JSON 直接放在 {@code config/talking_weapon/studio_workspace/} 下，
 * 游戏运行时与 Studio 共用同一份文件，避免额外“发布回运行时”的搬运层。
 */
public final class StudioWorkspacePaths {

    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String WORKSPACE_DIRNAME = "studio_workspace";

    private StudioWorkspacePaths() {
    }

    public static Path getWorkspaceRoot() {
        return getConfigRoot()
                .resolve(CONFIG_SUBDIR)
                .resolve(WORKSPACE_DIRNAME);
    }

    public static Path getAuthoredFilePath(String filename) {
        return getWorkspaceRoot().resolve(filename);
    }

    public static Path getLegacyConfigPath(String filename) {
        return getConfigRoot()
                .resolve(CONFIG_SUBDIR)
                .resolve(filename);
    }

    public static void ensureWorkspaceRootExists() throws IOException {
        Files.createDirectories(getWorkspaceRoot());
    }

    public static boolean migrateLegacyFileIfMissing(Path workspacePath, Path legacyPath, String logPrefix) {
        if (workspacePath == null || legacyPath == null) {
            return false;
        }
        if (Files.exists(workspacePath) || !Files.isRegularFile(legacyPath)) {
            return false;
        }
        try {
            Files.createDirectories(workspacePath.getParent());
            Files.copy(legacyPath, workspacePath, StandardCopyOption.REPLACE_EXISTING);
            if (logPrefix != null && !logPrefix.isBlank()) {
                System.out.println(logPrefix + "已将旧配置迁移到 Studio workspace：" + workspacePath);
            }
            return true;
        } catch (IOException e) {
            if (logPrefix != null && !logPrefix.isBlank()) {
                System.err.println(logPrefix + "迁移旧配置失败：" + e.getMessage());
            }
            return false;
        }
    }

    private static Path getConfigRoot() {
        Path configDir = null;
        try {
            configDir = FMLPaths.CONFIGDIR.get();
        } catch (Throwable ignored) {
        }
        return configDir != null ? configDir : Path.of("config");
    }
}
