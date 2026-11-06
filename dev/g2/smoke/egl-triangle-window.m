/*
 * G2 M3.0 smoke: the window-surface path, on the staged ANGLE (Metal) pair.
 *
 * The pbuffer smoke (egl-triangle.c) proves the engine ↔ ANGLE contract;
 * this proves the one remaining upstream claim M3 rests on: ANGLE's Metal
 * backend accepts a CAMetalLayer as an EGL window surface — NSWindow →
 * CAMetalLayer-backed NSView → eglCreateWindowSurface → ES3 triangle →
 * pre-swap readback → ~3 s of on-screen presents. That call is what the M3
 * shell's EglRenderContext is built on (NOTES.md §"M3 design").
 *
 * A red run here is fallback F1's trigger (NOTES.md): render into an
 * IOSurface-backed texture instead and present it through the layer via
 * EGL_ANGLE_metal_texture_client_buffer, Chromium-style.
 *
 * Needs a GUI session (opens a real window); CI compiles it only
 * (run-window.sh --compile-only). Esc or the close button ends it early.
 * Writes a PPM of the pre-swap framebuffer if a path is given (argv[1]).
 *
 * Exit: 0 PASS, 1 FAIL, 2 EGL/driver unavailable.
 */
#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#import <EGL/egl.h>
#import <EGL/eglext.h>
#import <GLES3/gl3.h>
#import <stdio.h>
#import <stdlib.h>

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

/* Present-loop length: long enough to eyeball the triangle, short enough to
 * feel automatic. Esc or closing the window ends it sooner. */
static const NSTimeInterval SMOKE_SECONDS = 3.0;

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
    fprintf(stderr, "egl-triangle-window: FAIL: %s (eglError=0x%04x glError=0x%04x)\n",
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
        fprintf(stderr, "egl-triangle-window: shader compile failed:\n%s\n", log);
        return 0;
    }
    return s;
}

/* NSView with a CAMetalLayer backing layer — exactly what ANGLE's Metal
 * backend takes as the native window (and what the M3 shell will hand it).
 * The layer is set explicitly: on current macOS AppKit ignores a +layerClass
 * override for backing layers (the view still gets NSViewBackingLayer, which
 * does not answer setDevice:), so hand it the layer Chromium-style. */
@interface TriView : NSView
@end
@implementation TriView
- (instancetype)initWithFrame:(NSRect)r {
    self = [super initWithFrame:r];
    if (self) {
        CAMetalLayer *layer = [CAMetalLayer layer];
        layer.device = MTLCreateSystemDefaultDevice();
        /* Retina calibration: drawableSize = point size × backing scale.
         * Re-synced below once the window is actually on a screen. */
        CGFloat scale = self.window.backingScaleFactor ?: [[NSScreen mainScreen] backingScaleFactor];
        layer.contentsScale = scale;
        layer.drawableSize = CGSizeMake(NSWidth(r) * scale, NSHeight(r) * scale);
        self.layer = layer;
        self.wantsLayer = YES;
    }
    return self;
}
@end

int main(int argc, char **argv) {
    const char *ppm = argc > 1 ? argv[1] : NULL;

    /* App without a bundle: claim the regular policy so the window can be
     * key and front; the present loop pumps events by hand (no app.run). */
    NSApplication *app = [NSApplication sharedApplication];
    [app setActivationPolicy:NSApplicationActivationPolicyRegular];

    NSRect frame = NSMakeRect(0, 0, 512, 384);
    NSWindow *win = [[NSWindow alloc] initWithContentRect:frame
                                                styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
                                                  backing:NSBackingStoreBuffered
                                                    defer:NO];
    win.title = @"macTAK M3.0 — window-surface smoke";
    win.releasedWhenClosed = NO;
    win.contentView = [[TriView alloc] initWithFrame:frame];
    [win center];
    [win makeKeyAndOrderFront:nil];
    [app activate];

    CAMetalLayer *layer = (CAMetalLayer *)win.contentView.layer;
    CGFloat scale = win.backingScaleFactor;
    layer.contentsScale = scale;
    layer.drawableSize = CGSizeMake(frame.size.width * scale, frame.size.height * scale);

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
    if (dpy == EGL_NO_DISPLAY) { fprintf(stderr, "egl-triangle-window: no EGL display\n"); return 2; }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(dpy, &major, &minor)) {
        fprintf(stderr, "egl-triangle-window: eglInitialize failed\n");
        return 2;
    }
    printf("egl-triangle-window: EGL %d.%d vendor=\"%s\" platform=%s\n", major, minor,
           eglQueryString(dpy, EGL_VENDOR), backend);

    /* Window-surface config: same ES3/RGBA8 as the pbuffer smoke, now
     * EGL_WINDOW_BIT. Depth/stencil first; a backend that offers no such
     * window config falls back to plain RGBA8. */
    EGLConfig cfg;
    EGLint ncfg = 0;
    const EGLint winAttrsD24S8[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    if (eglChooseConfig(dpy, winAttrsD24S8, &cfg, 1, &ncfg) && ncfg >= 1) {
        printf("egl-triangle-window: config: RGBA8 + D24S8 window\n");
    } else {
        const EGLint winAttrsPlain[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        if (!eglChooseConfig(dpy, winAttrsPlain, &cfg, 1, &ncfg) || ncfg < 1)
            return fail("eglChooseConfig (ES3 window, RGBA8)");
        printf("egl-triangle-window: config: RGBA8 window (no depth/stencil)\n");
    }

    /* The claim under test. */
    EGLSurface surf = eglCreateWindowSurface(dpy, cfg, (__bridge void *)layer, NULL);
    if (surf == EGL_NO_SURFACE) {
        fprintf(stderr,
                "egl-triangle-window: eglCreateWindowSurface refused the CAMetalLayer "
                "(eglError=0x%04x) — F1 trigger: IOSurface client-buffer path (NOTES.md §M3)\n",
                (unsigned)eglGetError());
        return 1;
    }
    printf("egl-triangle-window: eglCreateWindowSurface accepted the CAMetalLayer\n");

    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (ctx == EGL_NO_CONTEXT) return fail("eglCreateContext (ES 3)");
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) return fail("eglMakeCurrent");

    printf("egl-triangle-window: GL_VENDOR=\"%s\"\n", glGetString(GL_VENDOR));
    printf("egl-triangle-window: GL_RENDERER=\"%s\"\n", glGetString(GL_RENDERER));
    printf("egl-triangle-window: GL_VERSION=\"%s\"\n", glGetString(GL_VERSION));
    printf("egl-triangle-window: GLSL=\"%s\"\n", glGetString(GL_SHADING_LANGUAGE_VERSION));

    /* Surface size in pixels is what the engine's getWidth/getHeight feed on;
     * log it beside the layer's own numbers to eyeball the calibration. */
    EGLint W = 0, H = 0;
    eglQuerySurface(dpy, surf, EGL_WIDTH, &W);
    eglQuerySurface(dpy, surf, EGL_HEIGHT, &H);
    printf("egl-triangle-window: surface=%dx%dpx drawable=%.0fx%.0f backingScale=%.2f\n",
           (int)W, (int)H, (double)layer.drawableSize.width, (double)layer.drawableSize.height,
           (double)scale);
    if (W <= 0 || H <= 0) return fail("eglQuerySurface");

    GLuint vs = shader(GL_VERTEX_SHADER, VS), fs = shader(GL_FRAGMENT_SHADER, FS);
    if (!vs || !fs) return fail("shader compile");
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs); glAttachShader(prog, fs); glLinkProgram(prog);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) return fail("glLinkProgram");

    /* Same triangle as the pbuffer smoke: full-height, centre covered. */
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

    /* Read back BEFORE the first swap: after eglSwapBuffers the back buffer's
     * content is undefined per the EGL spec. */
    unsigned char *px = malloc((size_t)W * (size_t)H * 4);
    if (!px) return fail("malloc");
    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
    if (glGetError() != GL_NO_ERROR) return fail("glReadPixels");

    const unsigned char *c = &px[((H / 2) * W + W / 2) * 4];   /* centre: triangle */
    const unsigned char *k = &px[((H - 4) * W + 4) * 4];       /* top-left: clear  */
    printf("egl-triangle-window: centre=(%u,%u,%u,%u) corner=(%u,%u,%u,%u)\n",
           c[0], c[1], c[2], c[3], k[0], k[1], k[2], k[3]);
    int centreOk = c[0] > 240 && c[1] > 100 && c[1] < 160 && c[2] < 16;
    int cornerOk = k[0] < 16 && k[1] < 16 && k[2] > 48 && k[2] < 80;

    if (ppm) {
        FILE *f = fopen(ppm, "wb");
        if (f) {
            fprintf(f, "P6\n%d %d\n255\n", (int)W, (int)H);
            for (int y = H - 1; y >= 0; y--)          /* GL is bottom-up */
                for (int x = 0; x < W; x++) fwrite(&px[(y * W + x) * 4], 1, 3, f);
            fclose(f);
            printf("egl-triangle-window: wrote %s\n", ppm);
        } else {
            fprintf(stderr, "egl-triangle-window: WARN: could not write %s\n", ppm);
        }
    }
    free(px);

    /* Present loop: redraw + swap each frame, pump the app's events by hand,
     * stop at the deadline or when the user closes the window / hits Esc. */
    NSTimeInterval deadline = [NSDate timeIntervalSinceReferenceDate] + SMOKE_SECONDS;
    int frames = 0;
    BOOL quit = NO;
    while (!quit) {
        glClearColor(0.0f, 0.0f, 0.25f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(prog);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        eglSwapBuffers(dpy, surf);
        frames++;
        NSEvent *ev = [app nextEventMatchingMask:NSEventMaskAny
                                       untilDate:[NSDate dateWithTimeIntervalSinceNow:0.03]
                                          inMode:NSDefaultRunLoopMode
                                         dequeue:YES];
        if (ev) {
            if (ev.type == NSEventTypeKeyDown && ev.keyCode == 53)   /* Esc */
                quit = YES;
            else
                [app sendEvent:ev];
        }
        if (![win isVisible]) quit = YES;   /* closed (or hidden) by the user */
        if ([NSDate timeIntervalSinceReferenceDate] >= deadline) quit = YES;
    }
    printf("egl-triangle-window: presented %d frame(s) in %.1f s\n", frames, (double)SMOKE_SECONDS);

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);

    if (!centreOk || !cornerOk) return fail("pixel check");
    printf("egl-triangle-window: PASS\n");
    return 0;
}
