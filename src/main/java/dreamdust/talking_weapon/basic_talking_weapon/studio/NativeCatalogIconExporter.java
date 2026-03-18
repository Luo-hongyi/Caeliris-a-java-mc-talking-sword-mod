package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 导出 Studio 使用的 native tag 图标包。
 * <p>
 * 规则：
 * <ul>
 * <li>优先导出真实图标：item / block-item / spawn egg / bucket / effect / 部分 block particle / fluid texture</li>
 * <li>若不存在天然贴图，则导出占位 PNG，保证每个 native tag 都有可显示的图标文件</li>
 * </ul>
 */
public final class NativeCatalogIconExporter {

    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final int ICON_SIZE = 32;
    private static final int ICON_BUFFER_SIZE = 256;
    private static final int ICON_BACKGROUND_RGBA = 0xFFE2E2E2;
    private static final float ICON_BACKGROUND_CHANNEL = 0xE2 / 255.0F;
    private static final String LEGACY_MANIFEST_FILENAME = "native_catalog.icons.json";

    private NativeCatalogIconExporter() {
    }

    public static ExportResult exportAllIcons() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Native catalog icon export must run on the render thread.");
        }

        Minecraft mc = Minecraft.getInstance();
        NativeCatalogSnapshotExporter.SnapshotRoot snapshot = NativeCatalogSnapshotExporter.buildSnapshot();
        Path iconRoot = NativeCatalogSnapshotExporter.getIconDirectory();

        int exported = 0;
        int rendered = 0;
        int placeholders = 0;

        try {
            Files.deleteIfExists(FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(LEGACY_MANIFEST_FILENAME));
            clearIconDirectory(iconRoot);
            Files.createDirectories(iconRoot);
            for (NativeCatalogSnapshotExporter.SnapshotEntry entry : snapshot.entries) {
                if (!DetailTag.parse(entry.tag).isSpecific()) {
                    continue;
                }
                IconOutcome outcome = exportOne(mc, iconRoot, entry);
                exported++;
                if (outcome.realIcon) {
                    rendered++;
                } else {
                    placeholders++;
                }
            }
            return new ExportResult(iconRoot, exported, rendered, placeholders);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export native catalog icons", e);
        } finally {
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void clearIconDirectory(Path iconRoot) throws IOException {
        if (!Files.exists(iconRoot)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(iconRoot)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            if (!path.equals(iconRoot)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to clear icon path: " + path, e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static IconOutcome exportOne(Minecraft mc, Path iconRoot, NativeCatalogSnapshotExporter.SnapshotEntry entry)
            throws IOException {
        String relativePath = relativeIconPathOf(entry);
        Path outputPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(relativePath);
        Files.createDirectories(outputPath.getParent());

        NativeImage nativeImage = null;
        NativeImage flattenedImage = null;
        String source = "placeholder";
        boolean realIcon = false;
        try {
            try {
                nativeImage = tryRenderRealIcon(mc, entry);
            } catch (Exception ignored) {
                nativeImage = null;
                source = "placeholder_after_error";
            }
            if (nativeImage != null) {
                flattenedImage = flattenOntoBackground(nativeImage, ICON_BACKGROUND_RGBA);
                flattenedImage.writeToFile(outputPath);
                source = "rendered";
                realIcon = true;
            } else {
                writePlaceholder(entry, outputPath);
            }
        } finally {
            if (flattenedImage != null) {
                flattenedImage.close();
            }
            if (nativeImage != null) {
                nativeImage.close();
            }
        }

        if (!realIcon) {
            source = source.startsWith("placeholder") ? source : "placeholder";
        }

        IconOutcome outcome = new IconOutcome();
        outcome.relativePath = relativePath;
        outcome.source = source;
        outcome.realIcon = realIcon;
        return outcome;
    }

    private static NativeImage tryRenderRealIcon(Minecraft mc, NativeCatalogSnapshotExporter.SnapshotEntry entry) {
        Map<String, String> metadata = entry.metadata != null ? entry.metadata : Map.of();
        String iconKind = metadata.get("iconKind");
        String iconId = metadata.get("iconId");

        if ("item".equals(iconKind) && iconId != null) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(iconId));
            if (item != null) {
                return renderItemStackIcon(mc, new ItemStack(item));
            }
        }

        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(entry.namespace, entry.path);

        if ("effect".equals(entry.registryType)) {
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
            if (effect != null) {
                return renderEffectIcon(mc, effect);
            }
        }

        if ("block".equals(entry.registryType) && "block".equals(iconKind)) {
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block != null) {
                return renderBlockParticleIcon(mc, block);
            }
        }

        if ("fluid".equals(entry.registryType)) {
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid != null) {
                NativeImage fluidTexture = renderFluidTextureIcon(mc, fluid);
                if (fluidTexture != null) {
                    return fluidTexture;
                }
            }
        }

        return null;
    }

    private static NativeImage renderItemStackIcon(Minecraft mc, ItemStack stack) {
        TextureTarget target = new TextureTarget(ICON_SIZE, ICON_SIZE, true, Minecraft.ON_OSX);
        boolean projectionBackedUp = false;
        boolean modelViewPushed = false;
        try {
            target.setClearColor(
                    ICON_BACKGROUND_CHANNEL,
                    ICON_BACKGROUND_CHANNEL,
                    ICON_BACKGROUND_CHANNEL,
                    1.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.backupProjectionMatrix();
            projectionBackedUp = true;
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0F, ICON_SIZE, ICON_SIZE, 0.0F, 1000.0F, 3000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushPose();
            modelViewPushed = true;
            modelViewStack.setIdentity();
            modelViewStack.translate(0.0F, 0.0F, -2000.0F);
            RenderSystem.applyModelViewMatrix();

            MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(new BufferBuilder(ICON_BUFFER_SIZE));
            GuiGraphics graphics = new GuiGraphics(mc, bufferSource);
            graphics.renderFakeItem(stack, (ICON_SIZE - 16) / 2, (ICON_SIZE - 16) / 2);
            graphics.flush();

            return Screenshot.takeScreenshot(target);
        } finally {
            PoseStack modelViewStack = RenderSystem.getModelViewStack();
            if (modelViewPushed) {
                modelViewStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
            if (projectionBackedUp) {
                RenderSystem.restoreProjectionMatrix();
            }
            target.unbindWrite();
            target.destroyBuffers();
        }
    }

    private static NativeImage renderEffectIcon(Minecraft mc, MobEffect effect) {
        TextureAtlasSprite sprite = mc.getMobEffectTextures().get(effect);
        return sprite != null ? scaleSprite(sprite, ICON_SIZE, 0xFFFFFFFF) : null;
    }

    private static NativeImage renderBlockParticleIcon(Minecraft mc, Block block) {
        TextureAtlasSprite sprite = mc.getBlockRenderer()
                .getBlockModelShaper()
                .getParticleIcon(block.defaultBlockState());
        return sprite != null ? scaleSprite(sprite, ICON_SIZE, 0xFFFFFFFF) : null;
    }

    private static NativeImage renderFluidTextureIcon(Minecraft mc, Fluid fluid) {
        ResourceLocation stillTexture = IClientFluidTypeExtensions.of(fluid).getStillTexture();
        if (stillTexture == null) {
            return null;
        }

        ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(
                stillTexture.getNamespace(),
                "textures/" + stillTexture.getPath() + ".png");

        try (var stream = mc.getResourceManager().open(texturePath)) {
            NativeImage source = NativeImage.read(stream);
            try {
                int tint = IClientFluidTypeExtensions.of(fluid).getTintColor();
                return scaleNativeImage(source, ICON_SIZE, tint);
            } finally {
                source.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static NativeImage scaleSprite(TextureAtlasSprite sprite, int size, int tint) {
        NativeImage target = new NativeImage(size, size, false);
        int sourceWidth = Math.max(1, sprite.contents().width());
        int sourceHeight = Math.max(1, sprite.contents().height());

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int srcX = x * sourceWidth / size;
                int srcY = y * sourceHeight / size;
                int rgba = sprite.getPixelRGBA(0, srcX, srcY);
                target.setPixelRGBA(x, y, multiplyTint(rgba, tint));
            }
        }
        return target;
    }

    private static NativeImage scaleNativeImage(NativeImage source, int size, int tint) {
        NativeImage target = new NativeImage(size, size, false);
        int sourceWidth = Math.max(1, source.getWidth());
        int sourceHeight = Math.max(1, source.getHeight());

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int srcX = x * sourceWidth / size;
                int srcY = y * sourceHeight / size;
                int rgba = source.getPixelRGBA(srcX, srcY);
                target.setPixelRGBA(x, y, multiplyTint(rgba, tint));
            }
        }
        return target;
    }

    private static int multiplyTint(int rgba, int argbTint) {
        int r = rgba & 0xFF;
        int g = (rgba >> 8) & 0xFF;
        int b = (rgba >> 16) & 0xFF;
        int a = (rgba >> 24) & 0xFF;

        int tintA = (argbTint >> 24) & 0xFF;
        int tintR = (argbTint >> 16) & 0xFF;
        int tintG = (argbTint >> 8) & 0xFF;
        int tintB = argbTint & 0xFF;

        int outR = r * tintR / 255;
        int outG = g * tintG / 255;
        int outB = b * tintB / 255;
        int outA = a * tintA / 255;
        return outR | (outG << 8) | (outB << 16) | (outA << 24);
    }

    private static NativeImage flattenOntoBackground(NativeImage source, int backgroundRgba) {
        NativeImage target = new NativeImage(source.getWidth(), source.getHeight(), false);
        int bgR = backgroundRgba & 0xFF;
        int bgG = (backgroundRgba >> 8) & 0xFF;
        int bgB = (backgroundRgba >> 16) & 0xFF;

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgba = source.getPixelRGBA(x, y);
                int srcR = rgba & 0xFF;
                int srcG = (rgba >> 8) & 0xFF;
                int srcB = (rgba >> 16) & 0xFF;
                int srcA = (rgba >> 24) & 0xFF;

                int outR = (srcR * srcA + bgR * (255 - srcA)) / 255;
                int outG = (srcG * srcA + bgG * (255 - srcA)) / 255;
                int outB = (srcB * srcA + bgB * (255 - srcA)) / 255;
                target.setPixelRGBA(x, y, outR | (outG << 8) | (outB << 16) | (0xFF << 24));
            }
        }

        return target;
    }

    private static void writePlaceholder(NativeCatalogSnapshotExporter.SnapshotEntry entry, Path outputPath) throws IOException {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color bg = placeholderColor(entry);
            g.setColor(bg);
            g.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 8, 8);

            g.setColor(new Color(255, 255, 255, 36));
            g.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE / 2, 8, 8);

            g.setColor(new Color(0, 0, 0, 60));
            g.drawRoundRect(0, 0, ICON_SIZE - 1, ICON_SIZE - 1, 8, 8);

            String label = placeholderLabel(entry);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, label.length() > 2 ? 11 : 14));
            FontMetrics metrics = g.getFontMetrics();
            int textX = (ICON_SIZE - metrics.stringWidth(label)) / 2;
            int textY = (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(label, textX, textY);
        } finally {
            g.dispose();
        }

        ImageIO.write(image, "PNG", outputPath.toFile());
    }

    private static Color placeholderColor(NativeCatalogSnapshotExporter.SnapshotEntry entry) {
        String key = entry.kind + ":" + entry.registryType;
        return switch (key) {
            case "native_specific:item", "native_generic:item" -> new Color(0xC46A2E);
            case "native_specific:block", "native_generic:block" -> new Color(0x6B8C42);
            case "native_specific:entity_type", "native_generic:entity_type" -> new Color(0x7C4D9D);
            case "native_specific:effect", "native_generic:effect" -> new Color(0x2F8F9D);
            case "native_specific:enchantment", "native_generic:enchantment" -> new Color(0x4361B3);
            case "native_specific:fluid", "native_generic:fluid" -> new Color(0x2D6FB7);
            case "native_specific:biome", "native_generic:biome" -> new Color(0x2F7D32);
            case "native_specific:structure", "native_generic:structure" -> new Color(0x7B5B3E);
            case "native_specific:dimension", "native_generic:dimension" -> new Color(0x4A4A72);
            case "native_specific:poi", "native_generic:poi" -> new Color(0xA36C2B);
            default -> entry.kind.endsWith("generic") ? new Color(0x666666) : new Color(0x4F5D75);
        };
    }

    private static String placeholderLabel(NativeCatalogSnapshotExporter.SnapshotEntry entry) {
        if (entry.kind.endsWith("generic")) {
            return "#";
        }
        return switch (entry.registryType) {
            case "item" -> "IT";
            case "block" -> "BL";
            case "entity_type" -> "EN";
            case "effect" -> "EF";
            case "enchantment" -> "EC";
            case "fluid" -> "FL";
            case "biome" -> "BM";
            case "structure" -> "ST";
            case "dimension" -> "DM";
            case "poi" -> "PO";
            default -> abbreviated(entry.displayName != null ? entry.displayName : entry.path);
        };
    }

    private static String abbreviated(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }

        String[] parts = value.trim().split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }

        String compact = value.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
        if (compact.length() >= 2) {
            return compact.substring(0, 2).toUpperCase(Locale.ROOT);
        }
        return compact.isEmpty() ? "?" : compact.toUpperCase(Locale.ROOT);
    }

    private static String relativeIconPathOf(NativeCatalogSnapshotExporter.SnapshotEntry entry) {
        if (entry.metadata != null) {
            String relative = entry.metadata.get("iconRelativePath");
            if (relative != null && !relative.isBlank()) {
                return relative;
            }
        }
        return NativeCatalogSnapshotExporter.buildIconRelativePath(
                DetailTag.parse(entry.tag),
                entry.registryType,
                ResourceLocation.fromNamespaceAndPath(entry.namespace, entry.path));
    }

    public static final class ExportResult {
        public final Path iconRoot;
        public final int totalCount;
        public final int renderedCount;
        public final int placeholderCount;

        public ExportResult(Path iconRoot, int totalCount, int renderedCount, int placeholderCount) {
            this.iconRoot = iconRoot;
            this.totalCount = totalCount;
            this.renderedCount = renderedCount;
            this.placeholderCount = placeholderCount;
        }
    }

    private static final class IconOutcome {
        String relativePath;
        String source;
        boolean realIcon;
    }
}
