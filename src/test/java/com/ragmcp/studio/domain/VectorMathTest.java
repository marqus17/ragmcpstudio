package com.ragmcp.studio.domain;

import com.ragmcp.studio.domain.service.VectorMath;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VectorMathTest {

    @Test
    void roundTripsFloatArrayToLittleEndianBytesAndBack() {
        float[] original = {0.0f, 1.5f, -2.25f, 3.1415927f, Float.MAX_VALUE, -Float.MIN_VALUE};

        byte[] bytes = VectorMath.toBytes(original);
        assertThat(bytes).hasSize(original.length * Float.BYTES);

        // Verify explicit little-endian byte order on the second float (1.5f = 0x3FC00000)
        ByteBuffer check = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(check.getFloat()).isEqualTo(1.5f);

        float[] restored = VectorMath.toFloats(bytes);
        assertThat(restored).containsExactly(original);
    }

    @Test
    void rejectsInvalidByteArrayLength() {
        assertThatThrownBy(() -> VectorMath.toFloats(new byte[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorMath.toFloats(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorMath.toBytes(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computesCosineSimilarityAccuratelyAcrossUnrolledAndTailElements() {
        // Length 7 exercises both the 4-way unrolled loop and the 3-element tail
        float[] a = {1f, 2f, 3f, 4f, 5f, 6f, 7f};
        float[] sameDirection = {2f, 4f, 6f, 8f, 10f, 12f, 14f};
        float[] oppositeDirection = {-1f, -2f, -3f, -4f, -5f, -6f, -7f};

        assertThat(VectorMath.cosineSimilarity(a, sameDirection)).isCloseTo(1.0, within(1e-9));
        assertThat(VectorMath.cosineSimilarity(a, oppositeDirection)).isCloseTo(-1.0, within(1e-9));

        float[] orthoA = {1f, 0f, 0f, 0f, 0f};
        float[] orthoB = {0f, 1f, 0f, 0f, 0f};
        assertThat(VectorMath.cosineSimilarity(orthoA, orthoB)).isCloseTo(0.0, within(1e-9));

        float[] v1 = {1f, 2f, 3f};
        float[] v2 = {4f, 5f, 6f};
        double expected = (1 * 4 + 2 * 5 + 3 * 6) / (Math.sqrt(14.0) * Math.sqrt(77.0));
        assertThat(VectorMath.cosineSimilarity(v1, v2)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void handlesZeroNormNullEmptyAndDimensionMismatchSafely() {
        float[] zero = {0f, 0f, 0f, 0f};
        float[] nonZero = {1f, 2f, 3f, 4f};
        float[] differentDim = {1f, 2f};

        assertThat(VectorMath.cosineSimilarity(zero, nonZero)).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(nonZero, zero)).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(nonZero, differentDim)).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(null, nonZero)).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(nonZero, null)).isEqualTo(0.0);
        assertThat(VectorMath.cosineSimilarity(new float[0], new float[0])).isEqualTo(0.0);
    }
}
