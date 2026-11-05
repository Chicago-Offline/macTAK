/*
 * G2 spike smoke: EGL + GL ES 3 on ANGLE (Metal backend), headless.
 *
 * Proves the pieces the engine needs from the GLES-stub slot: a display
 * from the Metal platform, a pbuffer surface, an ES3 context, shaders
 * compile/link, a draw lands in the framebuffer, glReadPixels reads it
 * back. Writes a PPM of the frame if a path is given.
 *
 * Exit: 0 PASS, 1 FAIL, 2 EGL/driver unavailable.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ANGLE platform enums: the pinned Khronos registry in src/khronos predates
 * EGL_ANGLE_platform_angle_metal. Values match angle/include/EGL/eglext_angle.h. */
#ifndef EGL_PLATFORM_ANGLE_ANGLE
#define EGL_PLATFORM_ANGLE_ANGLE 0x3202
#endif
#ifndef EGL_PLATFORM_ANGLE_TYPE_ANGLE
#define EGL_PLATFORM_ANGLE_TYPE_ANGLE 0x3203
#endif
#ifndef EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE
#define EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE 0x3489
#endif
#ifndef EGL_PLATFORM_ANGLE_TYPE_DEFAULT_ANGLE
#define EGL_PLATFORM_ANGLE_TYPE_DEFAULT_ANGLE 0x3206
#endif

#define W 256
#define H 256

static const char *VS =
    "#version 300 es\n"
    "layout(location=0) in vec2 pos;\n"
    "void main(){ gl_Position = vec4(pos, 0.0, 1.0); }\n";
static const char *FS =
    "#version 300 es\n"
    "precision mediump float;\n"
    "out vec4 color;\n"
    "void main(){ color = vec4(1.0, 0.5, 0.0, 1.0); }\n";

static int fail(const char *what) {
    fprintf(stderr, "egl-triangle: FAIL: %s (eglError=0x%04x glError=0x%04x)\n",
            what, (unsigned)eglGetError(), (unsigned)glGetError());
    return 1;
}

static GLuint shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        fprintf(stderr, "egl-triangle: shader compile failed:\n%s\n", log);
        return 0;
    }
    return s;
}

int main(int argc, char **argv) {
    const char *ppm = argc > 1 ? argv[1] : NULL;

    /* Display: ask ANGLE for Metal explicitly; fall back to the default platform. */
    EGLDisplay dpy = EGL_NO_DISPLAY;
    const char *backend = "default";
    PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (getPlatformDisplay) {
        const EGLint attrs[] = { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE };
        dpy = getPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, (void *)EGL_DEFAULT_DISPLAY, attrs);
        backend = "metal";
    }
    if (dpy == EGL_NO_DISPLAY) {
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        backend = "default";
    }
    if (dpy == EGL_NO_DISPLAY) { fprintf(stderr, "egl-triangle: no EGL display\n"); return 2; }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(dpy, &major, &minor)) { fprintf(stderr, "egl-triangle: eglInitialize failed\n"); return 2; }
    printf("egl-triangle: EGL %d.%d vendor=\"%s\" platform=%s\n", major, minor, eglQueryString(dpy, EGL_VENDOR), backend);

    const EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg; EGLint ncfg = 0;
    if (!eglChooseConfig(dpy, cfgAttrs, &cfg, 1, &ncfg) || ncfg < 1) return fail("eglChooseConfig (ES3 pbuffer, D24S8)");

    const EGLint pbAttrs[] = { EGL_WIDTH, W, EGL_HEIGHT, H, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttrs);
    if (surf == EGL_NO_SURFACE) return fail("eglCreatePbufferSurface");

    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) return fail("eglCreateContext (ES 3)");
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) return fail("eglMakeCurrent");

    printf("egl-triangle: GL_VENDOR=\"%s\"\n", glGetString(GL_VENDOR));
    printf("egl-triangle: GL_RENDERER=\"%s\"\n", glGetString(GL_RENDERER));
    printf("egl-triangle: GL_VERSION=\"%s\"\n", glGetString(GL_VERSION));
    printf("egl-triangle: GLSL=\"%s\"\n", glGetString(GL_SHADING_LANGUAGE_VERSION));

    GLuint vs = shader(GL_VERTEX_SHADER, VS), fs = shader(GL_FRAGMENT_SHADER, FS);
    if (!vs || !fs) return fail("shader compile");
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs); glAttachShader(prog, fs); glLinkProgram(prog);
    GLint linked = 0; glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) return fail("glLinkProgram");

    /* Full-height triangle covering the centre; corners stay clear colour. */
    static const GLfloat tri[] = { -0.6f, -0.6f,  0.6f, -0.6f,  0.0f, 0.6f };
    GLuint vao, vbo;
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof tri, tri, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);

    glViewport(0, 0, W, H);
    glClearColor(0.0f, 0.0f, 0.25f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glUseProgram(prog);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    if (glGetError() != GL_NO_ERROR) return fail("draw");

    static unsigned char px[W * H * 4];
    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
    if (glGetError() != GL_NO_ERROR) return fail("glReadPixels");

    const unsigned char *c = &px[((H / 2) * W + W / 2) * 4];   /* centre: triangle */
    const unsigned char *k = &px[((H - 4) * W + 4) * 4];       /* top-left: clear   */
    printf("egl-triangle: centre=(%u,%u,%u,%u) corner=(%u,%u,%u,%u)\n",
           c[0], c[1], c[2], c[3], k[0], k[1], k[2], k[3]);
    int centreOk = c[0] > 240 && c[1] > 100 && c[1] < 160 && c[2] < 16;
    int cornerOk = k[0] < 16 && k[1] < 16 && k[2] > 48 && k[2] < 80;

    if (ppm) {
        FILE *f = fopen(ppm, "wb");
        if (f) {
            fprintf(f, "P6\n%d %d\n255\n", W, H);
            for (int y = H - 1; y >= 0; y--)          /* GL is bottom-up */
                for (int x = 0; x < W; x++) fwrite(&px[(y * W + x) * 4], 1, 3, f);
            fclose(f);
            printf("egl-triangle: wrote %s\n", ppm);
        }
    }

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);

    if (!centreOk || !cornerOk) return fail("pixel check");
    printf("egl-triangle: PASS\n");
    return 0;
}
