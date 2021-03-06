package com.homm3.livewallpaper.core

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.utils.Array
import com.homm3.livewallpaper.core.Constants.Companion.FRAME_TIME
import com.homm3.livewallpaper.core.Constants.Companion.TILE_SIZE
import com.homm3.livewallpaper.parser.formats.H3m
import kotlin.random.Random

class Sprite(mapObject: H3m.Object, private val frames: Array<TextureAtlas.AtlasRegion>) {
    private val x = (mapObject.x + 1) * TILE_SIZE
    private val y = (mapObject.y + 1) * TILE_SIZE
    private val animationLength = frames.size * FRAME_TIME
    private var stateTime = animationLength * Random.nextFloat()

    private fun getFrameX(frame: TextureAtlas.AtlasRegion): Float {
        return x + frame.offsetX - frame.originalWidth
    }

    private fun getFrameY(frame: TextureAtlas.AtlasRegion): Float {
        return y - frame.offsetY - frame.packedHeight
    }

    fun render(batch: Batch, delta: Float) {
        stateTime = (stateTime % animationLength) + delta
        val frameIndex = ((stateTime / FRAME_TIME) % frames.size).toInt()
        val frame = frames.get(frameIndex)
        batch.draw(frame, getFrameX(frame), getFrameY(frame))
    }

    fun inViewport(camera: Camera, offset: Float): Boolean {
        val halfWidth = camera.viewportWidth / 2
        val leftSide = camera.position.x - halfWidth - offset
        val rightSide = camera.position.x + camera.viewportWidth - halfWidth + offset
        val isInViewportByX = leftSide < x && x < rightSide

        val halfHeight = camera.viewportHeight / 2
        val topSide = camera.position.y - halfHeight - offset
        val bottomSide = camera.position.y + camera.viewportHeight - halfHeight + offset
        val isInViewportByY = topSide < y && y < bottomSide

        return isInViewportByX && isInViewportByY
    }
}