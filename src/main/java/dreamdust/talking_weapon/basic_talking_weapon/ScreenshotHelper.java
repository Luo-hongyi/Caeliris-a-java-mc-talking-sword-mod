package dreamdust.talking_weapon.basic_talking_weapon;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 客户端截图工具：从 Minecraft 渲染目标捕获一帧，
 * 缩放到目标分辨率后，编码为 Base64 PNG 字符串供 LLM 调用。
 * 必须在渲染线程（isClientSide == true）中调用。
 */
public class ScreenshotHelper {

    /**
     * 截图并返回 Base64 编码的 PNG 字符串。
     *
     * @param targetWidth  缩放后目标宽度
     * @param targetHeight 缩放后目标高度
     * @return base64 编码的 PNG 数据
     */
    public static String captureAsBase64(int targetWidth, int targetHeight) {
        Minecraft mc = Minecraft.getInstance();

        // 用游戏内置方法截取当前帧
        NativeImage nativeImage = Screenshot.takeScreenshot(mc.getMainRenderTarget());

        int srcWidth = nativeImage.getWidth();
        int srcHeight = nativeImage.getHeight();

        try {
            // 将 NativeImage 像素数据写入 BufferedImage，同时缩放
            BufferedImage bufferedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

            for (int dstY = 0; dstY < targetHeight; dstY++) {
                for (int dstX = 0; dstX < targetWidth; dstX++) {
                    int srcX = dstX * srcWidth / targetWidth;
                    // Screenshot.takeScreenshot() 已在内部修正了 OpenGL Y 轴，直接映射即可
                    int srcY = dstY * srcHeight / targetHeight;

                    // NativeImage.getPixelRGBA 返回值：bits[0-7]=R, [8-15]=G, [16-23]=B, [24-31]=A
                    int rgba = nativeImage.getPixelRGBA(srcX, srcY);
                    int r = rgba & 0xFF;
                    int g = (rgba >> 8) & 0xFF;
                    int b = (rgba >> 16) & 0xFF;

                    bufferedImage.setRGB(dstX, dstY, (r << 16) | (g << 8) | b);
                }
            }

            // 编码为 PNG Base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (Exception e) {
            throw new RuntimeException("[TalkingWeapon] Screenshot encoding failed", e);
        } finally {
            nativeImage.close();
        }
    }
}
