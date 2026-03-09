package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.ScreenshotHelper;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 截图采集器：将当前游戏画面截图编码为 Base64 写入 AgentContext。
 * <p>
 * 受 {@link TalkingWeaponConfig#ENABLE_SCREENSHOT} 控制——
 * 禁用时此采集器为空操作，LLM 仅接收文本上下文。
 */
public class ScreenshotCollector implements IContextCollector {

    @Override
    public String getName() {
        return "screenshot";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (!TalkingWeaponConfig.ENABLE_SCREENSHOT.get()) return;

        int w = TalkingWeaponConfig.SCREENSHOT_WIDTH.get();
        int h = TalkingWeaponConfig.SCREENSHOT_HEIGHT.get();
        try {
            builder.screenshotBase64(ScreenshotHelper.captureAsBase64(w, h));
        } catch (Exception ignored) {
            // 截图失败不阻塞流程
        }
    }
}
