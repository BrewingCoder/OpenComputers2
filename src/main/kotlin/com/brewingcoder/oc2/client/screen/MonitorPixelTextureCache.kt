package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent

/**
 * Per-master-BE client-side cache of [DynamicTexture]s that hold the monitor's
 * HD pixel buffer. The server-side pixel buffer (ARGB ints) is the source of
 * truth; clients sync the buffer via the standard BE NBT path, and this cache
 * mirrors those bytes into a GPU texture so the renderer can sample it as an
 * in-world textured quad.
 *
 * Lifecycle:
 * - Allocated on first render for a master
 * - Reallocated when pixel dimensions change (group resize)
 * - Freed when player logs out (world-leave)
 *
 * Must be used on the render thread. Upload is skipped when the buffer hash
 * matches the last upload — 99% of frames are no-op.
 */
@EventBusSubscriber(modid = OpenComputers2.ID, value = [Dist.CLIENT])
object MonitorPixelTextureCache {

    private class Entry(
        val texture: DynamicTexture,
        var pxW: Int,
        var pxH: Int,
        var lastBufferHash: Int,
    )

    private val entries: MutableMap<BlockPos, Entry> = mutableMapOf()

    /**
     * Get (or allocate/reallocate) the GL texture ID for this master. Uploads
     * the current buffer if its hash differs from the last uploaded one.
     * Returns 0 on failure (which the renderer treats as "skip the pixel layer").
     */
    fun getOrUpload(masterPos: BlockPos, pxW: Int, pxH: Int, sourceArgb: IntArray): Int {
        RenderSystem.assertOnRenderThread()
        val existing = entries[masterPos]
        val needRealloc = existing == null || existing.pxW != pxW || existing.pxH != pxH
        val entry = if (needRealloc) {
            existing?.texture?.close()
            val img = NativeImage(NativeImage.Format.RGBA, pxW, pxH, false)
            val e = Entry(DynamicTexture(img), pxW, pxH, lastBufferHash = 0)
            entries[masterPos] = e
            OpenComputers2.LOGGER.debug("MonitorPixelTextureCache: allocate {} at {}×{}", masterPos, pxW, pxH)
            e
        } else existing!!

        val newHash = sourceArgb.contentHashCode()
        if (newHash != entry.lastBufferHash || needRealloc) {
            val img = entry.texture.pixels ?: return entry.texture.id
            // NativeImage.setPixelRGBA despite its name takes ABGR ints (native
            // little-endian byte order when read back as RGBA). Swap R↔B here.
            for (y in 0 until pxH) {
                val rowBase = y * pxW
                for (x in 0 until pxW) {
                    val argb = sourceArgb[rowBase + x]
                    val a = (argb ushr 24) and 0xFF
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                    img.setPixelRGBA(x, y, abgr)
                }
            }
            entry.texture.upload()
            entry.lastBufferHash = newHash
        }
        return entry.texture.id
    }

    fun release(masterPos: BlockPos) {
        RenderSystem.assertOnRenderThread()
        entries.remove(masterPos)?.texture?.close()
    }

    fun releaseAll() {
        RenderSystem.assertOnRenderThread()
        if (entries.isEmpty()) return
        OpenComputers2.LOGGER.debug("MonitorPixelTextureCache: releaseAll ({} entries)", entries.size)
        for ((_, e) in entries) e.texture.close()
        entries.clear()
    }

    @SubscribeEvent
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        RenderSystem.recordRenderCall { releaseAll() }
    }
}
