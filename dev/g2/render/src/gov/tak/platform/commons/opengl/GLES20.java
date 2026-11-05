package gov.tak.platform.commons.opengl;

/**
 * macOS runtime for the port jar's {@code GLES20} (which delegates to JOGL on
 * desktop). Static calls resolve through the superclass, so this routes the
 * engine's {@code gov.tak.platform.commons.opengl.GLES20.gl*} to the ANGLE shim.
 */
public class GLES20 extends android.opengl.GLES20 implements GLES20Constants {
    protected GLES20() { }
}
