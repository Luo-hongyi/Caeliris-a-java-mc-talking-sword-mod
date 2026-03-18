package dreamdust.talking_weapon.basic_talking_weapon.studio;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 轮询 Studio workspace manifest，并在检测到 Studio 侧提交后自动热重载到运行时。
 * <p>
 * 这是当前“Studio <-> 游戏”双向实时连接的游戏侧实现：
 * <ul>
 * <li>Minecraft -> Studio：Studio 前端轮询 manifest</li>
 * <li>Studio -> Minecraft：本类轮询同一个 manifest，并在 updatedBy=studio 时自动 reload</li>
 * </ul>
 */
public final class StudioWorkspaceAutoPublisher {

    private static final int POLL_INTERVAL_TICKS = 20;
    private int tickCounter = 0;
    private String lastObservedSignature = "";

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        tickCounter++;
        if (tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        Path manifestPath = StudioWorkspaceManifestFile.getPath();
        if (!Files.isRegularFile(manifestPath)) {
            lastObservedSignature = "";
            return;
        }

        try {
            StudioWorkspaceManifestFile manifest = StudioWorkspaceManifestFile.read(manifestPath);
            String currentSignature = manifest.signature(StudioWorkspaceManifestFile.VERSION_KEYS, true);
            if (currentSignature.equals(lastObservedSignature)) {
                return;
            }
            lastObservedSignature = currentSignature;

            if (!StudioWorkspaceManifestFile.UPDATED_BY_STUDIO.equals(manifest.updatedBy)) {
                return;
            }

            StudioWorkspacePublisher.PublishResult result = StudioWorkspacePublisher.publishWorkspace();
            System.out.println("[TalkingWeapon/Studio] Auto-published workspace changes: "
                    + result.publishedFiles.size() + " files.");
        } catch (Exception e) {
            Path logPath = StudioErrorLogger.log("auto publish studio workspace", e);
            if (logPath != null) {
                System.err.println("[TalkingWeapon/Studio] Auto publish failed: " + e
                        + " | detailed log: " + logPath.toAbsolutePath());
            } else {
                System.err.println("[TalkingWeapon/Studio] Auto publish failed: " + e);
            }
            e.printStackTrace();
        }
    }
}
