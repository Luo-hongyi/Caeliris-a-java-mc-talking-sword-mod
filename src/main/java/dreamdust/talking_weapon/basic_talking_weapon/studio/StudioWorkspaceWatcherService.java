package dreamdust.talking_weapon.basic_talking_weapon.studio;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监听 Studio authored workspace，并在文件变化后自动热重载到游戏运行时。
 * <p>
 * 当前监听范围仅包含 authored JSON：
 * <ul>
 * <li>custom_tags.json</li>
 * <li>tag_descriptions.json</li>
 * <li>tag_relations.json</li>
 * <li>personas.json</li>
 * <li>implanted_memories.json</li>
 * <li>location_registry.json</li>
 * </ul>
 * 其余参考文件（manifest、icons、snapshot）不参与反向热重载。
 */
public final class StudioWorkspaceWatcherService implements AutoCloseable {

    private static final String LOG_PREFIX = "[TalkingWeapon/StudioWatcher] ";
    private static final long DEBOUNCE_MILLIS = 250L;
    private static final Set<String> AUTHORED_FILENAMES = Set.of(
            "custom_tags.json",
            "tag_descriptions.json",
            "tag_relations.json",
            "personas.json",
            "implanted_memories.json",
            "agent_state.json",
            "location_registry.json");

    private static volatile StudioWorkspaceWatcherService INSTANCE;

    private final Path workspaceRoot;
    private final WatchService watchService;
    private final Thread watcherThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LinkedHashSet<String> pendingFiles = new LinkedHashSet<>();

    private volatile long lastRelevantChangeAt = 0L;

    private StudioWorkspaceWatcherService(Path workspaceRoot) throws IOException {
        this.workspaceRoot = workspaceRoot;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerWorkspaceRoot();
        this.watcherThread = new Thread(this::runLoop, "TalkingWeapon-StudioWatcher");
        this.watcherThread.setDaemon(true);
    }

    public static synchronized void startIfNeeded() {
        if (INSTANCE != null) {
            return;
        }
        try {
            StudioWorkspacePaths.ensureWorkspaceRootExists();
            StudioWorkspaceWatcherService service = new StudioWorkspaceWatcherService(
                    StudioWorkspacePaths.getWorkspaceRoot());
            service.watcherThread.start();
            INSTANCE = service;
            System.out.println(LOG_PREFIX + "已启动，监听目录：" + service.workspaceRoot.toAbsolutePath());
        } catch (Exception e) {
            Path logPath = StudioErrorLogger.log("start studio workspace watcher", e);
            if (logPath != null) {
                System.err.println(LOG_PREFIX + "启动失败：" + e + " | detailed log: " + logPath.toAbsolutePath());
            } else {
                System.err.println(LOG_PREFIX + "启动失败：" + e);
            }
            e.printStackTrace();
        }
    }

    public static synchronized void stopIfRunning() {
        if (INSTANCE == null) {
            return;
        }
        INSTANCE.close();
        INSTANCE = null;
    }

    private void runLoop() {
        try {
            while (running.get()) {
                WatchKey key = watchService.poll(250, TimeUnit.MILLISECONDS);
                if (key != null) {
                    handleEvents(key);
                }
                flushPendingReloadIfQuiet();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Path logPath = StudioErrorLogger.log("watch studio workspace", e);
            if (logPath != null) {
                System.err.println(LOG_PREFIX + "监听中断：" + e + " | detailed log: " + logPath.toAbsolutePath());
            } else {
                System.err.println(LOG_PREFIX + "监听中断：" + e);
            }
            e.printStackTrace();
        } finally {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleEvents(WatchKey key) {
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                markAllAuthoredFilesDirty();
                continue;
            }

            Object context = event.context();
            if (!(context instanceof Path changedPath)) {
                continue;
            }

            String filename = changedPath.getFileName().toString();
            if (!AUTHORED_FILENAMES.contains(filename)) {
                continue;
            }

            pendingFiles.add(filename);
            lastRelevantChangeAt = System.currentTimeMillis();
        }

        if (!key.reset() && running.get()) {
            try {
                registerWorkspaceRoot();
            } catch (IOException e) {
                throw new RuntimeException("Failed to re-register Studio workspace watcher", e);
            }
        }
    }

    private void markAllAuthoredFilesDirty() {
        pendingFiles.addAll(AUTHORED_FILENAMES);
        lastRelevantChangeAt = System.currentTimeMillis();
    }

    private void flushPendingReloadIfQuiet() {
        if (pendingFiles.isEmpty()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lastRelevantChangeAt;
        if (elapsed < DEBOUNCE_MILLIS) {
            return;
        }

        Set<String> changedFiles = new LinkedHashSet<>(pendingFiles);
        pendingFiles.clear();
        scheduleReload(changedFiles);
    }

    private void scheduleReload(Set<String> changedFiles) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            System.err.println(LOG_PREFIX + "Minecraft 实例尚未就绪，跳过本次 reload：" + changedFiles);
            return;
        }

        minecraft.execute(() -> {
            try {
                StudioWorkspacePublisher.PublishResult result = StudioWorkspacePublisher.publishWorkspace();
                System.out.println(LOG_PREFIX + "检测到 Studio 侧修改，已热重载："
                        + String.join(", ", changedFiles)
                        + " | personas=" + result.personaCount
                        + ", memories=" + result.memoryCount
                        + ", relations=" + result.relationCount
                        + ", locations=" + result.locationCount);
            } catch (Exception e) {
                Path logPath = StudioErrorLogger.log("reload studio workspace from watcher", e);
                if (logPath != null) {
                    System.err.println(LOG_PREFIX + "热重载失败：" + e
                            + " | files=" + changedFiles
                            + " | detailed log: " + logPath.toAbsolutePath());
                } else {
                    System.err.println(LOG_PREFIX + "热重载失败：" + e + " | files=" + changedFiles);
                }
                e.printStackTrace();
            }
        });
    }

    private void registerWorkspaceRoot() throws IOException {
        Files.createDirectories(workspaceRoot);
        workspaceRoot.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        watcherThread.interrupt();
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
    }
}
