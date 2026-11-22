package com.ragmcp.studio.domain.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VectorMath {

    private VectorMath() {
    }

    public static byte[] toBytes(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] toFloats(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException(
                    "Invalid float byte array length: " + bytes.length + " (must be a multiple of 4)");
        }
        int length = bytes.length / Float.BYTES;
        float[] result = new float[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }

        int len = a.length;
        int limit = len - (len & 3);

        double dot0 = 0.0;
        double dot1 = 0.0;
        double dot2 = 0.0;
        double dot3 = 0.0;

        double normA0 = 0.0;
        double normA1 = 0.0;
        double normA2 = 0.0;
        double normA3 = 0.0;

        double normB0 = 0.0;
        double normB1 = 0.0;
        double normB2 = 0.0;
        double normB3 = 0.0;

        int i = 0;
        for (; i < limit; i += 4) {
            double a0 = a[i];
            double a1 = a[i + 1];
            double a2 = a[i + 2];
            double a3 = a[i + 3];

            double b0 = b[i];
            double b1 = b[i + 1];
            double b2 = b[i + 2];
            double b3 = b[i + 3];

            dot0 += a0 * b0;
            dot1 += a1 * b1;
            dot2 += a2 * b2;
            dot3 += a3 * b3;

            normA0 += a0 * a0;
            normA1 += a1 * a1;
            normA2 += a2 * a2;
            normA3 += a3 * a3;

            normB0 += b0 * b0;
            normB1 += b1 * b1;
            normB2 += b2 * b2;
            normB3 += b3 * b3;
        }

        double dot = (dot0 + dot1) + (dot2 + dot3);
        double normA = (normA0 + normA1) + (normA2 + normA3);
        double normB = (normB0 + normB1) + (normB2 + normB3);

        for (; i < len; i++) {
            double av = a[i];
            double bv = b[i];
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }

        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0 || Double.isNaN(denominator) || Double.isInfinite(denominator)) {
            return 0.0;
        }

        double similarity = dot / denominator;
        if (Double.isNaN(similarity)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, similarity));
    }
}
