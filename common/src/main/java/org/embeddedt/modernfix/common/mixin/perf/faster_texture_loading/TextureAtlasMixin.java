package org.embeddedt.modernfix.common.mixin.perf.faster_texture_loading;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.embeddedt.modernfix.platform.ModernFixPlatformHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mixin(value = TextureAtlas.class, priority = 600)
@ClientOnlyMixin
public abstract class TextureAtlasMixin {
    @Shadow protected abstract ResourceLocation getResourceLocation(ResourceLocation location);

    @Shadow protected abstract Collection<TextureAtlasSprite.Info> getBasicSpriteInfos(ResourceManager resourceManager, Set<ResourceLocation> spriteLocations);

    private Map<ResourceLocation, Pair<Resource, NativeImage>> loadedImages = new ConcurrentHashMap<>();
    private boolean usingFasterLoad;
    private Collection<TextureAtlasSprite.Info> storedResults;
    /**
     * @author embeddedt
     * @reason simplify texture loading by loading whole image once, avoid slow PngInfo code
     */
    @Inject(method = "getBasicSpriteInfos", at = @At("HEAD"))
    private void loadImages(ResourceManager manager, Set<ResourceLocation> imageLocations, CallbackInfoReturnable<Collection<TextureAtlasSprite.Info>> cir) {
        usingFasterLoad = ModernFixPlatformHooks.isLoadingNormally();
    }

    @Redirect(method = "getBasicSpriteInfos", at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;", ordinal = 0))
    private Iterator<?> skipIteration(Set<?> instance, ResourceManager manager, Set<ResourceLocation> imageLocations) {
        // bail if Forge is erroring to avoid AT crashes
        if(!usingFasterLoad)
            return instance.iterator();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        ConcurrentLinkedQueue<TextureAtlasSprite.Info> results = new ConcurrentLinkedQueue<>();
        for(ResourceLocation location : imageLocations) {
            if(MissingTextureAtlasSprite.getLocation().equals(location))
                continue;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    ResourceLocation fileLocation = this.getResourceLocation(location);
                    Resource resource = manager.getResource(fileLocation);
                    NativeImage image = NativeImage.read(resource.getInputStream());
                    AnimationMetadataSection animData = resource.getMetadata(AnimationMetadataSection.SERIALIZER);
                    if (animData == null) {
                        animData = AnimationMetadataSection.EMPTY;
                    }
                    Pair<Integer, Integer> dimensions = animData.getFrameSize(image.getWidth(), image.getHeight());
                    loadedImages.put(location, Pair.of(resource, image));
                    results.add(new TextureAtlasSprite.Info(location, dimensions.getFirst(), dimensions.getSecond(), animData));
                } catch(IOException e) {
                    ModernFix.LOGGER.error("Using missing texture, unable to load {} : {}", location, e);
                } catch(RuntimeException e) {
                    ModernFix.LOGGER.error("Unable to parse metadata from {} : {}", location, e);
                }
            }, ModernFix.resourceReloadExecutor()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        storedResults = results;
        return Collections.emptyIterator();
    }

    @Inject(method = "getBasicSpriteInfos", at = @At("RETURN"))
    private void injectFastSprites(ResourceManager resourceManager, Set<ResourceLocation> spriteLocations, CallbackInfoReturnable<Collection<TextureAtlasSprite.Info>> cir) {
        if(usingFasterLoad)
            cir.getReturnValue().addAll(storedResults);
    }

    @Inject(method = "prepareToStitch", at = @At("HEAD"))
    private void initMap(CallbackInfoReturnable<TextureAtlas.Preparations> cir) {
        loadedImages = new ConcurrentHashMap<>();
    }

    @Inject(method = "prepareToStitch", at = @At("RETURN"))
    private void clearLoadedImages(CallbackInfoReturnable<TextureAtlas.Preparations> cir) {
        loadedImages = Collections.emptyMap();
        storedResults = null;
    }

    @Inject(method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite$Info;IIIII)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",
        at = @At("HEAD"), cancellable = true)
    private void loadFromExisting(ResourceManager resourceManager, TextureAtlasSprite.Info spriteInfo, int width, int height, int mipmapLevel, int originX, int originY, CallbackInfoReturnable<TextureAtlasSprite> cir) {
        if(!usingFasterLoad)
            return;
        Pair<Resource, NativeImage> pair = loadedImages.get(spriteInfo.name());
        if(pair == null) {
            ModernFix.LOGGER.error("Texture {} was not loaded in early stage", spriteInfo.name());
            cir.setReturnValue(null);
        } else {
            TextureAtlasSprite sprite = null;
            try {
                sprite = ModernFixPlatformHooks.loadTextureAtlasSprite((TextureAtlas)(Object)this, resourceManager, spriteInfo, pair.getFirst(), width, height, originX, originY, mipmapLevel, pair.getSecond());
            } catch(RuntimeException e) {
                ModernFix.LOGGER.error("Error loading texture {}: {}", spriteInfo.name(), e);
            } finally {
                try {
                    pair.getFirst().close();
                } catch(IOException ignored) {
                    // not much we can do
                }
            }
            cir.setReturnValue(sprite);
        }
    }
}
