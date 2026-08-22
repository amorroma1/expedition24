// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.tools

import com.avdesign.mfd24.geo.Morton
import com.avdesign.mfd24.geo.PoiFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Packs the curated CSV sources in `tools/poi/data` into the binary asset the watch face reads.
 *
 * Run by the `:app:packPoi` Gradle task, so the asset can never drift from the CSVs and never has
 * to be committed. `Morton.kt` and `PoiFormat.kt` are compiled from `shared/kotlin` into both this
 * tool and the app, which is what guarantees the writer and the reader agree byte for byte.
 *
 * Usage: `PoiPack <dataDir> <outputDir>`
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: PoiPack <dataDir> <outputDir>")
        exitProcess(2)
    }
    val dataDir = File(args[0])
    val outputDir = File(args[1])

    val records = ArrayList<Record>(16_384)
    var files = 0
    for (file in dataDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
        if (!file.isFile || !file.name.endsWith(".csv")) continue
        files++
        readCsv(file, records)
    }
    require(files > 0) { "no CSV sources found in ${dataDir.absolutePath}" }
    require(records.isNotEmpty()) { "no usable records in ${dataDir.absolutePath}" }

    // Unsigned sort: Morton keys routinely have the sign bit set.
    records.sortWith { a, b -> Morton.compareKeys(a.morton, b.morton) }

    outputDir.mkdirs()
    val output = File(outputDir, PoiFormat.ASSET_NAME)
    write(output, records)

    println(
        "PoiPack: ${records.size} records from $files file(s) -> " +
            "${output.absolutePath} (${output.length()} bytes)"
    )
}

private class Record(
    val morton: Int,
    val lat: Float,
    val lon: Float,
    val type: Int,
    val flags: Int,
    val code: ByteArray,
)

private fun readCsv(file: File, into: MutableList<Record>) {
    var lineNumber = 0
    file.forEachLine { raw ->
        lineNumber++
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
        if (line.startsWith("code,")) return@forEachLine

        val parts = line.split(',')
        require(parts.size >= 5) { "${file.name}:$lineNumber malformed: $raw" }

        val code = parts[0].trim().uppercase()
        require(code.length in 2..PoiFormat.CODE_BYTES) {
            "${file.name}:$lineNumber code '$code' must be 2..${PoiFormat.CODE_BYTES} characters"
        }
        val bytes = code.toByteArray(Charsets.US_ASCII)
        require(bytes.size == code.length) { "${file.name}:$lineNumber code '$code' is not ASCII" }

        val lat = parts[1].trim().toDouble()
        val lon = parts[2].trim().toDouble()
        require(lat in -90.0..90.0) { "${file.name}:$lineNumber latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "${file.name}:$lineNumber longitude out of range: $lon" }

        val type = parts[3].trim().toInt()
        val flags = parts[4].trim().toInt()
        require(type in 0..255 && flags in 0..255) {
            "${file.name}:$lineNumber type/flags must fit in a byte"
        }

        val key = Morton.encode(Morton.quantizeLon(lon), Morton.quantizeLat(lat))
        into.add(Record(key, lat.toFloat(), lon.toFloat(), type, flags, bytes))
    }
}

private fun write(output: File, records: List<Record>) {
    val size = PoiFormat.HEADER_BYTES + records.size * PoiFormat.RECORD_BYTES
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    buffer.putInt(PoiFormat.MAGIC)
    buffer.putShort(PoiFormat.VERSION.toShort())
    buffer.putShort(0)
    buffer.putInt(records.size)

    // buckets[b] = index of the first record whose top key byte is >= b, so a lookup can seed its
    // binary search with a range of a few dozen records instead of the whole file.
    val buckets = IntArray(PoiFormat.BUCKET_COUNT)
    var index = 0
    for (bucket in 0 until PoiFormat.BUCKET_COUNT) {
        while (index < records.size && Morton.bucketOf(records[index].morton) < bucket) index++
        buckets[bucket] = index
    }
    for (bucket in buckets) buffer.putInt(bucket)

    for (record in records) {
        buffer.putInt(record.morton)
        buffer.putFloat(record.lat)
        buffer.putFloat(record.lon)
        buffer.put(record.type.toByte())
        buffer.put(record.flags.toByte())
        for (i in 0 until PoiFormat.CODE_BYTES) {
            buffer.put(if (i < record.code.size) record.code[i] else 0)
        }
    }

    check(!buffer.hasRemaining()) { "wrote ${buffer.position()} of $size bytes" }
    output.writeBytes(buffer.array())
}
