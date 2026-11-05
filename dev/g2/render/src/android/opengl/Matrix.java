package android.opengl;

/**
 * macOS runtime for the {@code android.opengl.Matrix} API the engine's Java
 * render path uses (the transplant's bodies are "Stub!"). Column-major 4x4
 * float matrices in the OpenGL convention, same contract as the platform
 * class. Written from the public API documentation.
 */
public class Matrix {

    private Matrix() { }

    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < 16; i++) sm[smOffset + i] = 0f;
        for (int i = 0; i < 16; i += 5) sm[smOffset + i] = 1f;
    }

    /** result = lhs x rhs; result may not overlap either operand. */
    public static void multiplyMM(float[] result, int resultOffset,
                                  float[] lhs, int lhsOffset, float[] rhs, int rhsOffset) {
        for (int i = 0; i < 4; i++) {
            final float rhs0 = rhs[rhsOffset + 4 * i];
            final float rhs1 = rhs[rhsOffset + 4 * i + 1];
            final float rhs2 = rhs[rhsOffset + 4 * i + 2];
            final float rhs3 = rhs[rhsOffset + 4 * i + 3];
            for (int j = 0; j < 4; j++) {
                result[resultOffset + 4 * i + j] =
                      lhs[lhsOffset + j] * rhs0
                    + lhs[lhsOffset + 4 + j] * rhs1
                    + lhs[lhsOffset + 8 + j] * rhs2
                    + lhs[lhsOffset + 12 + j] * rhs3;
            }
        }
    }

    public static void multiplyMV(float[] resultVec, int resultVecOffset,
                                  float[] lhsMat, int lhsMatOffset, float[] rhsVec, int rhsVecOffset) {
        final float x = rhsVec[rhsVecOffset], y = rhsVec[rhsVecOffset + 1];
        final float z = rhsVec[rhsVecOffset + 2], w = rhsVec[rhsVecOffset + 3];
        for (int j = 0; j < 4; j++) {
            resultVec[resultVecOffset + j] =
                  lhsMat[lhsMatOffset + j] * x
                + lhsMat[lhsMatOffset + 4 + j] * y
                + lhsMat[lhsMatOffset + 8 + j] * z
                + lhsMat[lhsMatOffset + 12 + j] * w;
        }
    }

    public static void transposeM(float[] mTrans, int mTransOffset, float[] m, int mOffset) {
        for (int i = 0; i < 4; i++) {
            int mBase = i * 4 + mOffset;
            mTrans[i + mTransOffset] = m[mBase];
            mTrans[i + 4 + mTransOffset] = m[mBase + 1];
            mTrans[i + 8 + mTransOffset] = m[mBase + 2];
            mTrans[i + 12 + mTransOffset] = m[mBase + 3];
        }
    }

    public static boolean invertM(float[] mInv, int mInvOffset, float[] m, int mOffset) {
        final float a00 = m[mOffset], a01 = m[mOffset + 1], a02 = m[mOffset + 2], a03 = m[mOffset + 3];
        final float a10 = m[mOffset + 4], a11 = m[mOffset + 5], a12 = m[mOffset + 6], a13 = m[mOffset + 7];
        final float a20 = m[mOffset + 8], a21 = m[mOffset + 9], a22 = m[mOffset + 10], a23 = m[mOffset + 11];
        final float a30 = m[mOffset + 12], a31 = m[mOffset + 13], a32 = m[mOffset + 14], a33 = m[mOffset + 15];

        final float b00 = a00 * a11 - a01 * a10, b01 = a00 * a12 - a02 * a10, b02 = a00 * a13 - a03 * a10;
        final float b03 = a01 * a12 - a02 * a11, b04 = a01 * a13 - a03 * a11, b05 = a02 * a13 - a03 * a12;
        final float b06 = a20 * a31 - a21 * a30, b07 = a20 * a32 - a22 * a30, b08 = a20 * a33 - a23 * a30;
        final float b09 = a21 * a32 - a22 * a31, b10 = a21 * a33 - a23 * a31, b11 = a22 * a33 - a23 * a32;

        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0f) return false;
        det = 1f / det;

        mInv[mInvOffset] = (a11 * b11 - a12 * b10 + a13 * b09) * det;
        mInv[mInvOffset + 1] = (-a01 * b11 + a02 * b10 - a03 * b09) * det;
        mInv[mInvOffset + 2] = (a31 * b05 - a32 * b04 + a33 * b03) * det;
        mInv[mInvOffset + 3] = (-a21 * b05 + a22 * b04 - a23 * b03) * det;
        mInv[mInvOffset + 4] = (-a10 * b11 + a12 * b08 - a13 * b07) * det;
        mInv[mInvOffset + 5] = (a00 * b11 - a02 * b08 + a03 * b07) * det;
        mInv[mInvOffset + 6] = (-a30 * b05 + a32 * b02 - a33 * b01) * det;
        mInv[mInvOffset + 7] = (a20 * b05 - a22 * b02 + a23 * b01) * det;
        mInv[mInvOffset + 8] = (a10 * b10 - a11 * b08 + a13 * b06) * det;
        mInv[mInvOffset + 9] = (-a00 * b10 + a01 * b08 - a03 * b06) * det;
        mInv[mInvOffset + 10] = (a30 * b04 - a31 * b02 + a33 * b00) * det;
        mInv[mInvOffset + 11] = (-a20 * b04 + a21 * b02 - a23 * b00) * det;
        mInv[mInvOffset + 12] = (-a10 * b09 + a11 * b07 - a12 * b06) * det;
        mInv[mInvOffset + 13] = (a00 * b09 - a01 * b07 + a02 * b06) * det;
        mInv[mInvOffset + 14] = (-a30 * b03 + a31 * b01 - a32 * b00) * det;
        mInv[mInvOffset + 15] = (a20 * b03 - a21 * b01 + a22 * b00) * det;
        return true;
    }

    public static void orthoM(float[] m, int mOffset, float left, float right,
                              float bottom, float top, float near, float far) {
        if (left == right) throw new IllegalArgumentException("left == right");
        if (bottom == top) throw new IllegalArgumentException("bottom == top");
        if (near == far) throw new IllegalArgumentException("near == far");
        final float rw = 1f / (right - left), rh = 1f / (top - bottom), rd = 1f / (far - near);
        setIdentityM(m, mOffset);
        m[mOffset] = 2f * rw;
        m[mOffset + 5] = 2f * rh;
        m[mOffset + 10] = -2f * rd;
        m[mOffset + 12] = -(right + left) * rw;
        m[mOffset + 13] = -(top + bottom) * rh;
        m[mOffset + 14] = -(far + near) * rd;
    }

    public static void frustumM(float[] m, int offset, float left, float right,
                                float bottom, float top, float near, float far) {
        if (left == right) throw new IllegalArgumentException("left == right");
        if (top == bottom) throw new IllegalArgumentException("top == bottom");
        if (near == far) throw new IllegalArgumentException("near == far");
        if (near <= 0f) throw new IllegalArgumentException("near <= 0.0f");
        if (far <= 0f) throw new IllegalArgumentException("far <= 0.0f");
        final float rw = 1f / (right - left), rh = 1f / (top - bottom), rd = 1f / (near - far);
        for (int i = 0; i < 16; i++) m[offset + i] = 0f;
        m[offset] = 2f * near * rw;
        m[offset + 5] = 2f * near * rh;
        m[offset + 8] = (right + left) * rw;
        m[offset + 9] = (top + bottom) * rh;
        m[offset + 10] = (far + near) * rd;
        m[offset + 11] = -1f;
        m[offset + 14] = 2f * far * near * rd;
    }

    public static void perspectiveM(float[] m, int offset, float fovy, float aspect, float zNear, float zFar) {
        final float f = 1f / (float) Math.tan(fovy * (Math.PI / 360.0));
        final float rangeReciprocal = 1f / (zNear - zFar);
        for (int i = 0; i < 16; i++) m[offset + i] = 0f;
        m[offset] = f / aspect;
        m[offset + 5] = f;
        m[offset + 10] = (zFar + zNear) * rangeReciprocal;
        m[offset + 11] = -1f;
        m[offset + 14] = 2f * zFar * zNear * rangeReciprocal;
    }

    public static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[12 + mi] += m[mi] * x + m[4 + mi] * y + m[8 + mi] * z;
        }
    }

    public static void translateM(float[] tm, int tmOffset, float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 12; i++) tm[tmOffset + i] = m[mOffset + i];
        for (int i = 0; i < 4; i++) {
            int tmi = tmOffset + i, mi = mOffset + i;
            tm[12 + tmi] = m[mi] * x + m[4 + mi] * y + m[8 + mi] * z + m[12 + mi];
        }
    }

    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int mi = mOffset + i;
            m[mi] *= x;
            m[4 + mi] *= y;
            m[8 + mi] *= z;
        }
    }

    public static void scaleM(float[] sm, int smOffset, float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            int smi = smOffset + i, mi = mOffset + i;
            sm[smi] = m[mi] * x;
            sm[4 + smi] = m[4 + mi] * y;
            sm[8 + smi] = m[8 + mi] * z;
            sm[12 + smi] = m[12 + mi];
        }
    }

    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        rm[rmOffset + 3] = 0; rm[rmOffset + 7] = 0; rm[rmOffset + 11] = 0;
        rm[rmOffset + 12] = 0; rm[rmOffset + 13] = 0; rm[rmOffset + 14] = 0; rm[rmOffset + 15] = 1;
        a *= (float) (Math.PI / 180.0f);
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        if (1f == x && 0f == y && 0f == z) {
            rm[rmOffset + 5] = c; rm[rmOffset + 10] = c; rm[rmOffset + 6] = s; rm[rmOffset + 9] = -s;
            rm[rmOffset + 1] = 0; rm[rmOffset + 2] = 0; rm[rmOffset + 4] = 0; rm[rmOffset + 8] = 0; rm[rmOffset] = 1;
        } else if (0f == x && 1f == y && 0f == z) {
            rm[rmOffset] = c; rm[rmOffset + 10] = c; rm[rmOffset + 8] = s; rm[rmOffset + 2] = -s;
            rm[rmOffset + 1] = 0; rm[rmOffset + 4] = 0; rm[rmOffset + 6] = 0; rm[rmOffset + 9] = 0; rm[rmOffset + 5] = 1;
        } else if (0f == x && 0f == y && 1f == z) {
            rm[rmOffset] = c; rm[rmOffset + 5] = c; rm[rmOffset + 1] = s; rm[rmOffset + 4] = -s;
            rm[rmOffset + 2] = 0; rm[rmOffset + 6] = 0; rm[rmOffset + 8] = 0; rm[rmOffset + 9] = 0; rm[rmOffset + 10] = 1;
        } else {
            float len = length(x, y, z);
            if (1f != len) { float r = 1f / len; x *= r; y *= r; z *= r; }
            float nc = 1f - c, xy = x * y, yz = y * z, zx = z * x, xs = x * s, ys = y * s, zs = z * s;
            rm[rmOffset] = x * x * nc + c; rm[rmOffset + 4] = xy * nc - zs; rm[rmOffset + 8] = zx * nc + ys;
            rm[rmOffset + 1] = xy * nc + zs; rm[rmOffset + 5] = y * y * nc + c; rm[rmOffset + 9] = yz * nc - xs;
            rm[rmOffset + 2] = zx * nc - ys; rm[rmOffset + 6] = yz * nc + xs; rm[rmOffset + 10] = z * z * nc + c;
        }
    }

    public static void rotateM(float[] rm, int rmOffset, float[] m, int mOffset, float a, float x, float y, float z) {
        float[] r = new float[16];
        setRotateM(r, 0, a, x, y, z);
        multiplyMM(rm, rmOffset, m, mOffset, r, 0);
    }

    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        float[] tmp = new float[32];
        setRotateM(tmp, 0, a, x, y, z);
        multiplyMM(tmp, 16, m, mOffset, tmp, 0);
        System.arraycopy(tmp, 16, m, mOffset, 16);
    }

    public static void setLookAtM(float[] rm, int rmOffset, float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ, float upX, float upY, float upZ) {
        float fx = centerX - eyeX, fy = centerY - eyeY, fz = centerZ - eyeZ;
        float rlf = 1f / length(fx, fy, fz);
        fx *= rlf; fy *= rlf; fz *= rlf;
        float sx = fy * upZ - fz * upY, sy = fz * upX - fx * upZ, sz = fx * upY - fy * upX;
        float rls = 1f / length(sx, sy, sz);
        sx *= rls; sy *= rls; sz *= rls;
        float ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        rm[rmOffset] = sx; rm[rmOffset + 1] = ux; rm[rmOffset + 2] = -fx; rm[rmOffset + 3] = 0f;
        rm[rmOffset + 4] = sy; rm[rmOffset + 5] = uy; rm[rmOffset + 6] = -fy; rm[rmOffset + 7] = 0f;
        rm[rmOffset + 8] = sz; rm[rmOffset + 9] = uz; rm[rmOffset + 10] = -fz; rm[rmOffset + 11] = 0f;
        rm[rmOffset + 12] = 0f; rm[rmOffset + 13] = 0f; rm[rmOffset + 14] = 0f; rm[rmOffset + 15] = 1f;
        translateM(rm, rmOffset, -eyeX, -eyeY, -eyeZ);
    }
}
