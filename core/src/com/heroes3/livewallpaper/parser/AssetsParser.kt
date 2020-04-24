package com.heroes3.livewallpaper.parser

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.badlogic.gdx.math.Rectangle
import java.io.*
import java.util.*
import java.util.zip.Deflater

private typealias PackedFrames = MutableMap<String, Def.Frame>

class AssetsParser(private val lodFileInputStream: InputStream) {
    companion object {
        private val transparent = byteArrayOf(
            0x00.toByte(),
            0x40.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x80.toByte(),
            0xff.toByte(),
            0x80.toByte(),
            0x40.toByte()
        )
        private val fixedPalette = byteArrayOf(
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0, 0, 0,
            0, 0, 0
        )
        private val ignoredFiles = listOf(
            // TODO event, random dwelling, random town
            "arrow.def", "avwattack.def", "adag.def",
            "avwmon1.def", "avwmon2.def", "avwmon3.def", "avwmon4.def", "avwmon5.def", "avwmon6.def",
            "avarnd1.def", "avarnd2.def", "avarnd3.def", "avarnd4.def", "avarnd5.def", "avtrndm0.def"
        )
    }

    private val lodReader = LodReader(lodFileInputStream)
    private val packer = PixmapPacker(2048, 2048, Pixmap.Format.RGBA4444, 0, false)
    private val terrainRegex = Regex("([a-z]+[0-9]+.pcx)_([0-1]+)", RegexOption.IGNORE_CASE)

    private fun writePageHeader(writer: Writer, filename: String, packer: PixmapPacker) {
        writer.append("\n")
        writer.append("$filename\n")
        writer.append("size: ${packer.pageWidth},${packer.pageHeight}\n")
        writer.append("format: ${packer.pageFormat.name}\n")
        writer.append("filter: Nearest,Nearest\n")
        writer.append("repeat: none\n")
    }

    private fun writeFrame(
        writer: Writer,
        name: String,
        index: String,
        rect: Rectangle,
        frame: Def.Frame
    ) {
        val spriteName = name.toLowerCase(Locale.ROOT).replace(".def", "")
        writer.append("$spriteName\n")
        writer.append("  rotate: false\n")
        writer.append("  xy: ${rect.x.toInt()}, ${rect.y.toInt()}\n")
        writer.append("  size: ${frame.width}, ${frame.height}\n")
        writer.append("  orig: ${frame.fullWidth}, ${frame.fullHeight}\n")
        writer.append("  offset: ${frame.x}, ${frame.y}\n")
        writer.append("  index: ${index}\n")
    }

    private fun readDefFile(fileStream: InputStream, file: Lod.File): Def {
        val defContentStream = lodReader.readFileContent(file)
        val defReader = DefReader(defContentStream)
        val def = defReader.read()
        def.lodFile = file
        System.arraycopy(fixedPalette, 0, def.rawPalette, 0, fixedPalette.size)
        return def
    }

    private fun makePixmap(frame: Def.Frame): Pixmap {
        val pngWriter = PngWriter()
        val pngData = pngWriter.create(
            frame.width,
            frame.height,
            frame.parentGroup.parentDef.rawPalette,
            transparent,
            frame.data
        )
        return Pixmap(pngData, 0, pngData.size)
    }

    private fun makeTerrainPixmap(frame: Def.Frame): Pixmap {
        val image = makePixmap(frame)
        val fullImage = Pixmap(frame.fullWidth, frame.fullHeight, Pixmap.Format.RGBA4444)
        fullImage.drawPixmap(image, frame.x, frame.y)
        frame.x = 0
        frame.y = 0
        frame.width = frame.fullWidth
        frame.height = frame.fullHeight
        return fullImage
    }

    private fun packTerrainFrame(frame: Def.Frame, acc: PackedFrames): PackedFrames {
        val index = 0
        val frameName = "${frame.frameName}_${index}"
        packer.pack(frameName, makeTerrainPixmap(frame))
        acc[frameName] = frame
        return acc
    }

    private fun packSpriteFrame(frame: Def.Frame, acc: PackedFrames): PackedFrames {
        val frameName = frame.frameName
        packer.pack(frameName, makePixmap(frame))
        acc[frameName] = frame
        return acc
    }

    @Throws(IOException::class)
    fun parseLodToAtlas(outputDirectory: File, atlasName: String) {
        val sprites = mutableListOf<Lod.File>()
        val defList = lodReader
            .read()
            .files
            .filter { lodFile ->
                val isDef = lodFile.name.endsWith(".def", true)
                val isIgnored = ignoredFiles.any { it.equals(lodFile.name, true) }
                isDef && !isIgnored
            }

        defList.filterTo(sprites, fun(file): Boolean {
            return file.fileType == Lod.FileType.TERRAIN
        })
        defList.filterTo(sprites, fun(file): Boolean {
            val isExtraSprite = file.fileType == Lod.FileType.SPRITE
                && file.name.startsWith("av", true)
            val isMapSprite = file.fileType == Lod.FileType.MAP
            return isExtraSprite || isMapSprite
        })

        val packedFrames = sprites
            .sortedBy { it.offset }
            .map { file -> readDefFile(lodFileInputStream, file) }
            .flatMap { def -> def.groups.flatMap { group -> group.frames } }
            .distinctBy { it.frameName }
            .foldRightIndexed(
                mutableMapOf<String, Def.Frame>(),
                { index, frame, acc ->
                    when (frame.parentGroup.parentDef.lodFile.fileType) {
                        Lod.FileType.TERRAIN -> packTerrainFrame(frame, acc)
                        Lod.FileType.SPRITE -> packSpriteFrame(frame, acc)
                        Lod.FileType.MAP -> packSpriteFrame(frame, acc)
                        else -> acc
                    }
                }
            )

        writePackerContent(packedFrames, outputDirectory, atlasName)
    }

    private fun writePng(stream: OutputStream, pixmap: Pixmap) {
        val writer = PixmapIO.PNG(pixmap.width * pixmap.height * 1.5f.toInt())
        try {
            writer.setFlipY(false)
            writer.setCompression(Deflater.DEFAULT_COMPRESSION)
            writer.write(stream, pixmap)
        } finally {
            writer.dispose()
        }
    }

    private fun writePackerContent(sprites: PackedFrames, outputDirectory: File, atlasName: String) {
        if (outputDirectory.exists()) {
            outputDirectory.deleteRecursively()
        }
        outputDirectory.mkdirs()
        val writer = outputDirectory.resolve("${atlasName}.atlas").writer()

        packer.pages.forEachIndexed { index, page ->
            val pngName = "${atlasName}_${index}.png"
            writePageHeader(writer, pngName, packer)
            writePng(outputDirectory.resolve(pngName).outputStream(), page.pixmap)

            page.rects.forEach { entry ->
                val rectName = entry.key
                val rect = entry.value
                val frame = sprites[rectName] ?: return@forEach
                frame
                    .parentGroup
                    .filenames
                    .forEachIndexed(fun(index, fileName) {
                        val defName = frame.parentGroup.parentDef.lodFile.name
                        val matchResult = terrainRegex.findAll(rectName).toList()
                        val isTerrain = matchResult.isNotEmpty()
                        if (isTerrain) {
                            val name = matchResult[0].groupValues[1]
                            val rotationIndex = matchResult[0].groupValues[2]

                            if (fileName == name) {
                                writeFrame(writer, "$defName/$index", rotationIndex, rect, frame)
                            }
                        } else {
                            if (fileName == rectName) {
                                writeFrame(writer, defName, index.toString(), rect, frame)
                            }
                        }
                    })
            }
        }

        writer.close()
    }
}