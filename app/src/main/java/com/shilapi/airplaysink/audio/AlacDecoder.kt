package com.shilapi.airplaysink.audio

import com.shilapi.airplaysink.util.SinkLog

/**
 * Apple Lossless (ALAC) decoder for the RAOP "raw" packet layout.
 *
 * This is a Java/Kotlin port of David Hammerton's decoder (`alac.c`, MIT licensed), which is the
 * decoder shairport-sync ships and which is byte-for-byte equivalent to Apple's `ag_dec.c`
 * (`dyn_decomp` / `dyn_get`) and `dp_dec.c` (`unpc_block`) in the way it computes the Rice
 * parameter, tracks the running mean, splits zero runs, and adapts the FIR coefficients.
 *
 * The RAOP stream carries one CPE (stereo channel pair) per RTP packet, 352 frames each, 16 bit,
 * 44.1 kHz, so the parameters below come straight from the ANNOUNCE `a=fmtp:` line rather than from
 * a magic cookie.
 */
class AlacDecoder(
    private val sampleSize: Int = 16,
    private val numChannels: Int = 2,
    val maxSamplesPerFrame: Int = 352,
    private val riceHistoryMult: Int = 40,
    private val riceInitialHistory: Int = 10,
    private val riceKModifier: Int = 14,
) : AudioDecoder {

    private companion object {
        const val RICE_THRESHOLD = 8
        const val ID_SCE = 0
        const val ID_CPE = 1
    }

    val bytesPerSample: Int = (sampleSize / 8) * numChannels

    private val predictErrorA = IntArray(maxSamplesPerFrame)
    private val predictErrorB = IntArray(maxSamplesPerFrame)
    private val samplesA = IntArray(maxSamplesPerFrame)
    private val samplesB = IntArray(maxSamplesPerFrame)
    private val uncompressedA = IntArray(maxSamplesPerFrame)
    private val uncompressedB = IntArray(maxSamplesPerFrame)

    private val coefficientTableA = IntArray(32)
    private val coefficientTableB = IntArray(32)

    /** Zero padded so the bit reader can look a few bytes past the end like the C original does. */
    private var buffer = ByteArray(0)
    private var length = 0
    private var position = 0
    private var bitAccumulator = 0

    /** True once a frame has decoded without hitting an unsupported construct. */
    override var lastError: String? = null
        private set

    override val maxFramesPerPacket: Int get() = maxSamplesPerFrame

    /**
     * Decodes one packet payload. [out] must hold at least `maxSamplesPerFrame * numChannels`
     * shorts. Returns samples-per-channel, or -1 when the frame cannot be decoded.
     */
    override fun decodeFrame(
        input: ByteArray,
        offset: Int,
        inputLength: Int,
        out: ShortArray,
    ): Int {
        lastError = null
        if (inputLength <= 0) {
            lastError = "empty payload"
            return -1
        }
        if (buffer.size < inputLength + 8) buffer = ByteArray(inputLength + 8)
        System.arraycopy(input, offset, buffer, 0, inputLength)
        java.util.Arrays.fill(buffer, inputLength, buffer.size, 0)
        length = inputLength
        position = 0
        bitAccumulator = 0

        val maxBytes = maxSamplesPerFrame * bytesPerSample
        var outputSamples = maxSamplesPerFrame
        var outputBytes = outputSamples * bytesPerSample
        if (outputBytes > maxBytes) {
            lastError = "output buffer too small"
            return -1
        }

        val element = readBits(3)
        when (element) {
            ID_SCE -> {
                readBits(4)
                readBits(12)
                val hasSize = readBits(1) == 1
                var uncompressedBytes = readBits(2)
                val isNotCompressed = readBits(1)
                if (hasSize) {
                    outputSamples = readBits(32)
                    if (outputSamples <= 0 || outputSamples > maxSamplesPerFrame) {
                        lastError = "frame claims $outputSamples samples"
                        return -1
                    }
                    outputBytes = outputSamples * bytesPerSample
                    if (outputBytes > maxBytes) {
                        lastError = "frame output exceeds buffer"
                        return -1
                    }
                }

                val readSampleSize = sampleSize - uncompressedBytes * 8
                if (isNotCompressed == 0) {
                    val coefficientCount = decodeMonoPredictor(readSampleSize, uncompressedBytes, outputSamples)
                    if (coefficientCount < 0) return -1
                } else {
                    for (i in 0 until outputSamples) {
                        val bits = readBits(sampleSize)
                        samplesA[i] = signExtend(bits, sampleSize)
                    }
                    uncompressedBytes = 0
                }
                writeMonoOutput(out, outputSamples)
            }

            ID_CPE -> {
                if (numChannels < 2) {
                    lastError = "stereo element in a $numChannels channel stream"
                    return -1
                }
                readBits(4)
                readBits(12)
                val hasSize = readBits(1) == 1
                var uncompressedBytes = readBits(2)
                val isNotCompressed = readBits(1)
                if (hasSize) {
                    outputSamples = readBits(32)
                    if (outputSamples <= 0 || outputSamples > maxSamplesPerFrame) {
                        lastError = "frame claims $outputSamples samples"
                        return -1
                    }
                    outputBytes = outputSamples * bytesPerSample
                    if (outputBytes > maxBytes) {
                        lastError = "frame output exceeds buffer"
                        return -1
                    }
                }

                val readSampleSize = sampleSize - (uncompressedBytes * 8) + 1
                var interlacingShift = 0
                var interlacingLeftWeight = 0

                if (isNotCompressed == 0) {
                    interlacingShift = readBits(8)
                    interlacingLeftWeight = readBits(8)

                    val countA = readChannelHeader(coefficientTableA)
                    if (countA < 0) return -1
                    val countB = readChannelHeader(coefficientTableB)
                    if (countB < 0) return -1

                    if (uncompressedBytes > 0) {
                        val bits = uncompressedBytes * 8
                        for (i in 0 until outputSamples) {
                            uncompressedA[i] = readBits(bits)
                            uncompressedB[i] = readBits(bits)
                        }
                    }

                    val riceModifierA = lastRiceModifierA
                    val riceModifierB = lastRiceModifierB
                    val quantA = lastQuantizationA
                    val quantB = lastQuantizationB
                    val typeA = lastPredictionTypeA
                    val typeB = lastPredictionTypeB

                    if (typeA != 0 || typeB != 0) {
                        lastError = "unsupported prediction type ($typeA/$typeB)"
                        return -1
                    }

                    entropyRiceDecode(
                        predictErrorA, outputSamples, readSampleSize,
                        riceInitialHistory, riceKModifier,
                        riceModifierA * riceHistoryMult / 4, (1 shl riceKModifier) - 1,
                    )
                    predictorDecompressFirAdapt(
                        predictErrorA, samplesA, outputSamples, readSampleSize,
                        coefficientTableA, countA, quantA,
                    )

                    entropyRiceDecode(
                        predictErrorB, outputSamples, readSampleSize,
                        riceInitialHistory, riceKModifier,
                        riceModifierB * riceHistoryMult / 4, (1 shl riceKModifier) - 1,
                    )
                    predictorDecompressFirAdapt(
                        predictErrorB, samplesB, outputSamples, readSampleSize,
                        coefficientTableB, countB, quantB,
                    )
                } else {
                    if (sampleSize <= 16) {
                        for (i in 0 until outputSamples) {
                            samplesA[i] = signExtend(readBits(sampleSize), sampleSize)
                            samplesB[i] = signExtend(readBits(sampleSize), sampleSize)
                        }
                    } else {
                        for (i in 0 until outputSamples) {
                            var a = readBits(16) shl (sampleSize - 16)
                            a = a or readBits(sampleSize - 16)
                            samplesA[i] = signExtend24(a)

                            var b = readBits(16) shl (sampleSize - 16)
                            b = b or readBits(sampleSize - 16)
                            samplesB[i] = signExtend24(b)
                        }
                    }
                    uncompressedBytes = 0
                    interlacingShift = 0
                    interlacingLeftWeight = 0
                }

                if (sampleSize != 16) {
                    lastError = "unsupported sample size $sampleSize"
                    return -1
                }
                deinterlace16(
                    samplesA, samplesB, out, numChannels, outputSamples,
                    interlacingShift, interlacingLeftWeight,
                )
            }

            else -> {
                lastError = "unsupported ALAC element $element"
                return -1
            }
        }

        return outputSamples
    }

    // ---------------------------------------------------------------- bit reader

    private fun byteAt(index: Int): Int =
        if (index < 0 || index >= length) 0 else buffer[index].toInt() and 0xFF

    private fun readBits16(bits: Int): Int {
        var result = (byteAt(position) shl 16) or (byteAt(position + 1) shl 8) or byteAt(position + 2)
        result = result shl bitAccumulator
        result = result and 0x00FFFFFF
        result = result ushr (24 - bits)
        val newAccumulator = bitAccumulator + bits
        position += newAccumulator shr 3
        bitAccumulator = newAccumulator and 7
        return result
    }

    private fun readBits(bits: Int): Int {
        var remaining = bits
        var result = 0
        if (remaining > 16) {
            remaining -= 16
            result = readBits16(16) shl remaining
        }
        result = result or readBits16(remaining)
        return result
    }

    private fun readBit(): Int {
        var result = byteAt(position)
        result = result shl bitAccumulator
        result = (result shr 7) and 1
        val newAccumulator = bitAccumulator + 1
        position += newAccumulator / 8
        bitAccumulator = newAccumulator % 8
        return result
    }

    private fun unreadBits(bits: Int) {
        val newAccumulator = bitAccumulator - bits
        position += newAccumulator shr 3
        bitAccumulator = newAccumulator and 7
        if (bitAccumulator < 0) bitAccumulator = -bitAccumulator
    }

    // ---------------------------------------------------------------- entropy

    private fun entropyDecodeValue(readSampleSize: Int, k: Int, riceKModifierMask: Int): Int {
        var x = 0
        while (x <= RICE_THRESHOLD && readBit() != 0) x++

        if (x > RICE_THRESHOLD) {
            var value = readBits(readSampleSize)
            value = value and (-1 ushr (32 - readSampleSize))
            x = value
        } else if (k != 1) {
            val extraBits = readBits(k)
            x *= ((1 shl k) - 1) and riceKModifierMask
            if (extraBits > 1) x += extraBits - 1 else unreadBits(1)
        }
        return x
    }

    private fun entropyRiceDecode(
        output: IntArray,
        outputSize: Int,
        readSampleSize: Int,
        riceInitialHistory: Int,
        riceKModifier: Int,
        riceHistoryMult: Int,
        riceKModifierMask: Int,
    ) {
        var history = riceInitialHistory
        var signModifier = 0
        var outputCount = 0

        while (outputCount < outputSize) {
            var k = 31 - riceKModifier - Integer.numberOfLeadingZeros((history shr 9) + 3)
            k = if (k < 0) k + riceKModifier else riceKModifier

            var decodedValue = entropyDecodeValue(readSampleSize, k, -1)
            decodedValue += signModifier
            var finalValue = (decodedValue + 1) / 2
            if (decodedValue and 1 != 0) finalValue = -finalValue
            output[outputCount] = finalValue

            signModifier = 0

            history += (decodedValue * riceHistoryMult) - ((history * riceHistoryMult) shr 9)
            if (decodedValue > 0xFFFF) history = 0xFFFF

            if (history < 128 && outputCount + 1 < outputSize) {
                signModifier = 1
                k = Integer.numberOfLeadingZeros(history) + ((history + 16) / 64) - 24
                val blockSize = entropyDecodeValue(16, k, riceKModifierMask)
                if (blockSize > 0) {
                    val available = outputSize - outputCount - 1
                    val zeroes = if (blockSize <= available) blockSize else available
                    if (zeroes > 0) output.fill(0, outputCount + 1, outputCount + 1 + zeroes)
                    outputCount += blockSize
                }
                if (blockSize > 0xFFFF) signModifier = 0
                history = 0
            }

            outputCount++
        }
    }

    // ---------------------------------------------------------------- predictors

    private var lastPredictionTypeA = 0
    private var lastQuantizationA = 0
    private var lastRiceModifierA = 0
    private var lastPredictionTypeB = 0
    private var lastQuantizationB = 0
    private var lastRiceModifierB = 0

    private fun readChannelHeader(coefficients: IntArray): Int {
        val predictionType = readBits(4)
        val quantization = readBits(4)
        val riceModifier = readBits(3)
        val coefficientCount = readBits(5)
        if (coefficientCount > coefficients.size) {
            lastError = "predictor coefficient count $coefficientCount"
            return -1
        }
        for (i in 0 until coefficientCount) {
            coefficients[i] = readBits(16).toShort().toInt()
        }
        if (coefficients === coefficientTableA) {
            lastPredictionTypeA = predictionType
            lastQuantizationA = quantization
            lastRiceModifierA = riceModifier
        } else {
            lastPredictionTypeB = predictionType
            lastQuantizationB = quantization
            lastRiceModifierB = riceModifier
        }
        return coefficientCount
    }

    private fun decodeMonoPredictor(
        readSampleSize: Int,
        uncompressedBytes: Int,
        outputSamples: Int,
    ): Int {
        readBits(8)
        readBits(8)
        val predictionType = readBits(4)
        val quantization = readBits(4)
        val riceModifier = readBits(3)
        val coefficientCount = readBits(5)
        if (coefficientCount > coefficientTableA.size) {
            lastError = "predictor coefficient count $coefficientCount"
            return -1
        }
        for (i in 0 until coefficientCount) {
            coefficientTableA[i] = readBits(16).toShort().toInt()
        }
        if (uncompressedBytes > 0) {
            val bits = uncompressedBytes * 8
            for (i in 0 until outputSamples) uncompressedA[i] = readBits(bits)
        }
        if (predictionType != 0) {
            lastError = "unsupported mono prediction type $predictionType"
            return -1
        }
        entropyRiceDecode(
            predictErrorA, outputSamples, readSampleSize,
            riceInitialHistory, riceKModifier,
            riceModifier * riceHistoryMult / 4, (1 shl riceKModifier) - 1,
        )
        predictorDecompressFirAdapt(
            predictErrorA, samplesA, outputSamples, readSampleSize,
            coefficientTableA, coefficientCount, quantization,
        )
        return coefficientCount
    }

    private fun predictorDecompressFirAdapt(
        errorBuffer: IntArray,
        bufferOut: IntArray,
        outputSize: Int,
        readSampleSize: Int,
        coefficients: IntArray,
        coefficientCount: Int,
        quantization: Int,
    ) {
        bufferOut[0] = errorBuffer[0]

        if (coefficientCount == 0) {
            if (outputSize <= 1) return
            System.arraycopy(errorBuffer, 1, bufferOut, 1, outputSize - 1)
            return
        }

        if (coefficientCount == 0x1F) {
            if (outputSize <= 1) return
            for (i in 0 until outputSize - 1) {
                bufferOut[i + 1] = signExtend(bufferOut[i] + errorBuffer[i + 1], readSampleSize)
            }
            return
        }

        for (i in 0 until coefficientCount) {
            bufferOut[i + 1] = signExtend(bufferOut[i] + errorBuffer[i + 1], readSampleSize)
        }

        var base = 0
        for (i in coefficientCount + 1 until outputSize) {
            val errorValue = errorBuffer[i]
            var sum = 0
            for (j in 0 until coefficientCount) {
                sum += (bufferOut[base + coefficientCount - j] - bufferOut[base]) * coefficients[j]
            }
            // The reference C shifts by (quantization - 1) and by (quantization); a zero shift count
            // is undefined there, so the rounding term is simply dropped if it ever occurs.
            var outValue = if (quantization > 0) (1 shl (quantization - 1)) + sum else sum
            outValue = outValue shr quantization
            outValue += bufferOut[base] + errorValue
            outValue = signExtend(outValue, readSampleSize)
            bufferOut[base + coefficientCount + 1] = outValue

            var remaining = errorValue
            if (remaining > 0) {
                var index = coefficientCount - 1
                while (index >= 0 && remaining > 0) {
                    val delta = bufferOut[base] - bufferOut[base + coefficientCount - index]
                    val sign = signOnly(delta)
                    coefficients[index] -= sign
                    val magnitude = delta * sign
                    remaining -= (magnitude shr quantization) * (coefficientCount - index)
                    index--
                }
            } else if (remaining < 0) {
                var index = coefficientCount - 1
                while (index >= 0 && remaining < 0) {
                    val delta = bufferOut[base] - bufferOut[base + coefficientCount - index]
                    val sign = -signOnly(delta)
                    coefficients[index] -= sign
                    val magnitude = delta * sign
                    remaining -= (magnitude shr quantization) * (coefficientCount - index)
                    index--
                }
            }

            base++
        }
    }

    // ---------------------------------------------------------------- output

    private fun writeMonoOutput(out: ShortArray, outputSamples: Int) {
        for (i in 0 until outputSamples) {
            out[i * numChannels] = samplesA[i].toShort()
        }
    }

    private fun deinterlace16(
        a: IntArray,
        b: IntArray,
        out: ShortArray,
        numChannels: Int,
        numSamples: Int,
        interlacingShift: Int,
        interlacingLeftWeight: Int,
    ) {
        if (numSamples <= 0) return
        if (interlacingLeftWeight != 0) {
            for (i in 0 until numSamples) {
                val midRight = a[i]
                val difference = b[i]
                val right = midRight - ((difference * interlacingLeftWeight) shr interlacingShift)
                val left = right + difference
                out[i * numChannels] = left.toShort()
                out[i * numChannels + 1] = right.toShort()
            }
            return
        }
        for (i in 0 until numSamples) {
            out[i * numChannels] = a[i].toShort()
            out[i * numChannels + 1] = b[i].toShort()
        }
    }

    private fun signExtend(value: Int, bits: Int): Int = (value shl (32 - bits)) shr (32 - bits)

    private fun signExtend24(value: Int): Int = (value shl 8) shr 8

    private fun signOnly(value: Int): Int = when {
        value < 0 -> -1
        value > 0 -> 1
        else -> 0
    }

    fun describe(): String =
        "ALAC ${sampleSize}bit ${numChannels}ch frame=$maxSamplesPerFrame " +
            "rice(hist=$riceHistoryMult initial=$riceInitialHistory k=$riceKModifier)"

    init {
        SinkLog.i("ALAC decoder ready: ${describe()}")
    }
}
