// main.swift — macTAK G2 M3.2: the app shell (host model A, NOTES §M3).
//
// This process owns both lifecycles. AppKit's main thread creates the
// NSWindow + CAMetalLayer, starts the JVM in-process through libjli
// (jni_boot.c), and upcalls WindowApp.start across JNI. The JVM spawns its
// own render thread (WindowApp.render) which owns the EGL context
// end-to-end; the main thread never touches EGL. The main thread keeps a
// poll timer: WindowApp.pollStatus says where the render thread is
// (RENDERING → FRAME_DONE → EXITED); on FRAME_DONE the window lingers for
// --linger ms so a human can eyeball the frame; then -- or on window close
// or a failure -- the shell asks WindowApp.stop, waits for EXITED (clean
// engine/EGL teardown on the render thread), and exits with WindowApp's
// code. The JVM is never destroyed: DestroyJavaVM blocks on lingering
// non-daemon engine threads, and System.exit is the proven RenderSmoke
// path — here taken by the shell, which owns the process.
//
// Exit codes: WindowApp's (0 PASS, 1 FAIL, 2 usage/environment, 3 EGL init
// failed); the shell adds nothing else — 2 also covers its own usage and
// bootstrap failures (libjli/JVM/class resolution, no Metal device).

import AppKit
import Metal
import Darwin

let SHELL = "mactak-shell"
// Line-buffer stdout: the gate runs redirect it to a file, and Swift's print
// is fully buffered there — without this, the shell's progress lines only
// surface at process exit (or never, when it is killed).
setvbuf(stdout, nil, _IOLBF, 0)

func shellFail(_ msg: String) -> Never {
    fputs("\(SHELL): \(msg)\n", stderr)
    exit(2)
}

struct ShellOptions {
    var classpath: String?
    var libs: [String] = []
    var props: [String] = []
    var egl: String?
    var gles: String?
    var w = 640, h = 480
    var frames = 5
    var settleMs: Int64 = 3000
    var lingerMs: Int64 = 4000
    var wait = false
    var out: String?
    var layers: [String] = []
    var basemaps: [String] = []
    var layerDb: String?
    var res = Double.nan
    var atLat = Double.nan
    var atLon = Double.nan
}

let USAGE = """
usage: \(SHELL) --classpath CP --lib PATH [--lib PATH ...] --egl PATH --gles PATH
                   [--prop JVM_OPTION ...] [--size WxH] [--frames N] [--settle MS]
                   [--linger MS | --wait] [--out FILE.png] [--layer FILE ...]
                   [--basemap FILE ...] [--layer-db DIR] [--res M] [--at LAT,LON]
  --classpath CP  the JVM classpath (built by dev/g2/shell/run.sh; the JVM
                  starts with -Djava.class.path=CP)
  --lib PATH      native library to System.load, in order (libtakengine,
                  libtakenginejni)
  --egl PATH      ANGLE libEGL.dylib; --gles PATH  ANGLE libGLESv2.dylib
  --prop OPT      a raw JVM option, e.g. -Djava.awt.headless=true (repeatable)
  --size WxH      window and surface size in pixels (default 640x480)
  --frames N      frames to render (default 5; plus the --settle tail)
  --settle MS     min wall time to keep rendering while layers load (default 3000)
  --linger MS     keep the window up this long after the frame is done, then
                  auto-close (default 4000)
  --wait          no auto-close; close the window yourself
  --out FILE      readback artifact (.png via ImageIO, else binary PPM)
  --layer FILE    vector layer (GeoJSON/GPKG/KML/SHP; repeatable)
  --basemap FILE  raster basemap (MBTiles; repeatable)
  --layer-db DIR  feature store dir (default: temp)
  --res M         camera resolution, metres/pixel (default: fit content bounds)
  --at LAT,LON    camera centre (default: content bounds centre)
"""

func parseSize(_ s: String, _ what: String) -> (Int, Int) {
    let p = s.split(separator: "x", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
    guard p.count == 2, let w = Int(p[0]), let h = Int(p[1]), w > 0, h > 0 else {
        shellFail("bad --\(what) \(s) (want WxH, positive)")
    }
    return (w, h)
}

func parseDouble(_ s: String, _ what: String) -> Double {
    guard let d = Double(s) else { shellFail("bad --\(what) \(s)") }
    return d
}

var o = ShellOptions()
var argv = Array(CommandLine.arguments.dropFirst())
var i = 0
while i < argv.count {
    switch argv[i] {
    case "--classpath":
        i += 1; guard i < argv.count else { shellFail("--classpath needs a value") }
        o.classpath = argv[i]
    case "--lib": i += 1; o.libs.append(argv[i])
    case "--prop": i += 1; o.props.append(argv[i])
    case "--egl": i += 1; o.egl = argv[i]
    case "--gles": i += 1; o.gles = argv[i]
    case "--size": i += 1; (o.w, o.h) = parseSize(argv[i], "size")
    case "--frames": i += 1; o.frames = Int(argv[i]) ?? 0
    case "--settle": i += 1; o.settleMs = Int64(argv[i]) ?? -1
    case "--linger": i += 1; o.lingerMs = Int64(argv[i]) ?? -1
    case "--wait": o.wait = true
    case "--out": i += 1; o.out = argv[i]
    case "--layer": i += 1; o.layers.append(argv[i])
    case "--basemap": i += 1; o.basemaps.append(argv[i])
    case "--layer-db": i += 1; o.layerDb = argv[i]
    case "--res": i += 1; o.res = parseDouble(argv[i], "res")
    case "--at":
        i += 1
        let ll = argv[i].split(separator: ",", maxSplits: 1).map(String.init)
        guard ll.count == 2 else { shellFail("bad --at \(argv[i]) (want LAT,LON)") }
        o.atLat = parseDouble(ll[0], "at"); o.atLon = parseDouble(ll[1], "at")
    case "-h", "--help", "--usage":
        print(USAGE); exit(0)
    default:
        fputs("\(SHELL): unknown option \(argv[i])\n\(USAGE)\n", stderr); exit(2)
    }
    i += 1
}
guard let cp = o.classpath, !cp.isEmpty else { fputs("\(SHELL): --classpath is required\n\(USAGE)\n", stderr); exit(2) }
guard !o.libs.isEmpty, o.egl != nil, o.gles != nil else { fputs("\(SHELL): --lib/--egl/--gles are required\n\(USAGE)\n", stderr); exit(2) }
guard o.frames >= 1 else { fputs("\(SHELL): --frames must be >= 1\n", stderr); exit(2) }
guard o.settleMs >= 0, o.lingerMs >= 0 else { fputs("\(SHELL): --settle/--linger must be >= 0\n", stderr); exit(2) }

// ---- JVM bootstrap (jni_boot.c) -----------------------------------------

var errBuf = [CChar](repeating: 0, count: 1024)

guard let libjli = mactak_find_libjli(&errBuf, errBuf.count) else {
    shellFail(String(cString: errBuf))
}
print("\(SHELL): libjli \(String(cString: libjli))")

var jvmOptions = ["-Djava.class.path=\(cp)"] + o.props
var cOptions: [UnsafeMutablePointer<CChar>?] = jvmOptions.map { strdup($0) }
let createRc = cOptions.withUnsafeMutableBufferPointer { p in
    mactak_create_jvm(p.baseAddress, Int32(p.count), &errBuf, errBuf.count)
}
cOptions.forEach { if let p = $0 { free(p) } }
if createRc != 0 {
    shellFail("creating the JVM failed (\(createRc)): \(String(cString: errBuf))")
}

let envNullable = mactak_env()
guard envNullable != nil else { shellFail("no JNIEnv after JNI_CreateJavaVM") }
let env = envNullable!
// env is the JNIEnv* — the pointer every JNI function takes as its first
// argument; its target is the function table (C: (*env)->FindClass(env, ...)).
// Two derefs reach the table struct; `env` itself is passed along each call.
let JNF = env.pointee!.pointee

// ---- JNI upcalls (Swift → WindowApp statics) -----------------------------
//
// The Args forms (CallStatic...MethodA) are used rather than the varargs
// ones: start() has 15 mixed-width parameters, and an explicit jvalue array
// does not depend on how Swift lays out a 15-deep varargs call.

func jniExceptionPending(_ during: String) -> Bool {
    if JNF.ExceptionCheck!(env) != 0 {
        JNF.ExceptionDescribe!(env)
        JNF.ExceptionClear!(env)
        fputs("\(SHELL): JNI exception while \(during)\n", stderr)
        return true
    }
    return false
}

func jniFindClass(_ name: String) -> jclass? {
    let cName = name.cString(using: .utf8)!
    let cls = JNF.FindClass!(env, cName)
    return jniExceptionPending("finding class \(name)") ? nil : cls
}

func jniStaticMethod(_ cls: jclass, _ name: String, _ sig: String) -> jmethodID? {
    let cName = name.cString(using: .utf8)!
    let cSig = sig.cString(using: .utf8)!
    let mid = JNF.GetStaticMethodID!(env, cls, cName, cSig)
    return jniExceptionPending("resolving \(name)\(sig)") ? nil : mid
}

func jniNewString(_ s: String) -> jstring? {
    let cs = s.cString(using: .utf8)!
    let js = JNF.NewStringUTF!(env, cs)
    return jniExceptionPending("creating string") ? nil : js
}

func jniNewStringArray(_ items: [String]) -> jobjectArray? {
    guard let cls = jniFindClass("java/lang/String") else { return nil }
    let arr = JNF.NewObjectArray!(env, jint(items.count), cls, nil)
    if jniExceptionPending("creating array") { return nil }
    for (n, s) in items.enumerated() {
        guard let js = jniNewString(s) else { return nil }
        JNF.SetObjectArrayElement!(env, arr, jint(n), js)
        if jniExceptionPending("filling array") { return nil }
    }
    return arr
}

let windowAppNullable = jniFindClass("mactak/render/WindowApp")
guard windowAppNullable != nil else { exit(2) }
let gWindowApp = windowAppNullable!

func jniCallInt(_ mid: jmethodID) -> jint {
    let v = JNF.CallStaticIntMethodA!(env, gWindowApp, mid, nil)
    if jniExceptionPending("calling") { exit(2) }
    return v
}

func jniCallVoid(_ mid: jmethodID) {
    JNF.CallStaticVoidMethodA!(env, gWindowApp, mid, nil)
    if jniExceptionPending("calling") { exit(2) }
}

// start(long layerPtr, int w, int h, String[] libs, String egl, String gles,
//       String[] layers, String[] basemaps, String layerDb, String out,
//       double res, double atLat, double atLon, int frames, long settleMs)
let SIG_START = "(JII[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
    + "[Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;DDDIJ)V"

guard let midStart = jniStaticMethod(gWindowApp, "start", SIG_START),
      let midPoll = jniStaticMethod(gWindowApp, "pollStatus", "()I"),
      let midExit = jniStaticMethod(gWindowApp, "exitCode", "()I"),
      let midStop = jniStaticMethod(gWindowApp, "stop", "()V")
else { exit(2) }

// No Metal device (headless Mac, VM without a GPU): the CAMetalLayer below
// would get no device and eglCreateWindowSurface would misreport that as an
// F1 trigger (#42). Bail here, like the M3.0 smoke does.
guard MTLCreateSystemDefaultDevice() != nil else {
    shellFail("no Metal device (MTLCreateSystemDefaultDevice returned nil)")
}

// ---- The window (AppKit main thread) -------------------------------------

final class MetalView: NSView {
    let metalLayer: CAMetalLayer

    override init(frame frameRect: NSRect) {
        metalLayer = CAMetalLayer()
        metalLayer.device = MTLCreateSystemDefaultDevice()
        // M3.2 pins contentsScale to 1: drawableSize == EGL surface == the
        // readback PNG, all the w,h the shell passes. Retina calibration
        // (drawableSize = point size x backing scale) is M3.3 (NOTES §M3).
        metalLayer.contentsScale = 1.0
        metalLayer.drawableSize = CGSize(width: frameRect.width, height: frameRect.height)
        super.init(frame: frameRect)
        self.layer = metalLayer          // set explicitly, Chromium-style: on
        self.wantsLayer = true           // current macOS a +layerClass override
    }                                    // is ignored for backing layers (M3.0)

    required init?(coder: NSCoder) { fatalError("no nib") }
}

final class Shell: NSObject, NSApplicationDelegate {
    let window: NSWindow
    let opt: ShellOptions
    let midPoll: jmethodID
    let midExit: jmethodID
    let midStop: jmethodID
    var frameDoneLogged = false
    var lingerDeadline: Date?
    var stopRequestedByUs = false
    var stopRequestedAt: Date?
    var lastSt = -1

    init(window: NSWindow, opt: ShellOptions, midPoll: jmethodID, midExit: jmethodID, midStop: jmethodID) {
        self.window = window
        self.opt = opt
        self.midPoll = midPoll
        self.midExit = midExit
        self.midStop = midStop
    }

    func tick() {
        let st = Int(jniCallInt(midPoll))
        if st != lastSt {
            print("\(SHELL): poll → \(st)")
            lastSt = st
        }
        if st == 1 && !frameDoneLogged {                       // PHASE_FRAME_DONE
            frameDoneLogged = true
            if opt.wait {
                print("\(SHELL): frame done — keeping the window up (--wait); close it to exit")
            } else {
                lingerDeadline = Date().addingTimeInterval(Double(opt.lingerMs) / 1000.0)
                print("\(SHELL): frame done — closing in \(Double(opt.lingerMs) / 1000.0) s (run with --wait or --linger N to change)")
            }
        }
        if st == 2 {                                           // PHASE_EXITED
            if !frameDoneLogged { print("\(SHELL): render failed — closing the window") }
            NSApp.terminate(nil)      // shouldTerminate: EXITED → .terminateNow
            return
        }
        if let dl = lingerDeadline, Date() >= dl, !opt.wait, !stopRequestedByUs {
            // Linger over: wind the render thread down from here, while the
            // window is still up, and let the next ticks see EXITED before
            // terminating. (Asking AppKit first would wedge — see
            // applicationShouldTerminate.)
            print("\(SHELL): linger elapsed — asking the render thread to wind down")
            jniCallVoid(midStop)
            stopRequestedByUs = true
            stopRequestedAt = Date()
        }
        if stopRequestedByUs, st != 2, let t0 = stopRequestedAt,
           Date().timeIntervalSince(t0) > 30 {
            // Teardown should take well under a second; a hang here means the
            // engine stalled on the render thread. Report and get out.
            fputs("\(SHELL): teardown did not finish within 30 s — exiting anyway\n", stderr)
            exit(Int32(jniCallInt(midExit)))
        }
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let st = Int(jniCallInt(midPoll))
        if st == 2 { return .terminateNow }
        // User-initiated quit (window close / ⌘Q) while the render thread is
        // still up. Returning .terminateLater here would wedge: AppKit then
        // runs a private nested run-loop mode inside -terminate:, where this
        // delegate's timers never fire, so NSApp.reply could never happen.
        // Instead: ask the render thread to wind down and wait for EXITED
        // with a bounded run-loop pump, then terminate for real.
        jniCallVoid(midStop)
        if !stopRequestedByUs { stopRequestedByUs = true }
        stopRequestedAt = Date()
        let deadline = Date().addingTimeInterval(10)
        while Int(jniCallInt(midPoll)) != 2 && Date() < deadline {
            RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.05))
        }
        if Int(jniCallInt(midPoll)) != 2 {
            fputs("\(SHELL): teardown did not finish within 10 s — exiting anyway\n", stderr)
            exit(Int32(jniCallInt(midExit)))
        }
        print("\(SHELL): teardown complete — terminating")
        return .terminateNow
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true                              // user-close flows into the path above
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.regular)         // bare binary, no bundle: claim the
app.activate()                            // regular policy so the window is key

let frame = NSRect(x: 0, y: 0, width: CGFloat(o.w), height: CGFloat(o.h))
let view = MetalView(frame: frame)
let window = NSWindow(contentRect: frame,
                      styleMask: [.titled, .closable],
                      backing: .buffered,
                      defer: false)
window.title = "macTAK M3.2 — shell"
window.isReleasedWhenClosed = false
window.contentView = view
window.center()
window.makeKeyAndOrderFront(nil)

let shell = Shell(window: window, opt: o, midPoll: midPoll, midExit: midExit, midStop: midStop)
app.delegate = shell

// The layer pointer crosses JNI as an opaque jlong; Java never dereferences
// it (the engine's createWindowSurface does). The view keeps the layer alive.
let layerPtr = UInt(bitPattern: unsafeBitCast(view.metalLayer, to: Int.self))
print("\(SHELL): CAMetalLayer @ 0x\(String(layerPtr, radix: 16)) drawable=\(o.w)x\(o.h) contentsScale=1")

var startArgs = [jvalue](repeating: jvalue(), count: 15)
startArgs[0].j = jlong(bitPattern: layerPtr)
startArgs[1].i = jint(o.w)
startArgs[2].i = jint(o.h)
startArgs[3].l = jniNewStringArray(o.libs)
startArgs[4].l = jniNewString(o.egl!)
startArgs[5].l = jniNewString(o.gles!)
startArgs[6].l = jniNewStringArray(o.layers)
startArgs[7].l = jniNewStringArray(o.basemaps)
startArgs[8].l = o.layerDb.flatMap { jniNewString($0) }
startArgs[9].l = o.out.flatMap { jniNewString($0) }
startArgs[10].d = jdouble(o.res)
startArgs[11].d = jdouble(o.atLat)
startArgs[12].d = jdouble(o.atLon)
startArgs[13].i = jint(o.frames)
startArgs[14].j = jlong(o.settleMs)
// 3..7 must exist (libs, egl, gles, layers, basemaps); 8 (layerDb) and
// 9 (out) are nullable by contract — WindowApp substitutes a temp dir and
// skips the frame write respectively.
for (n, a) in startArgs.enumerated() where n >= 3 && n <= 7 && a.l == nil {
    shellFail("jniNewString failed for start() argument \(n)")
}
JNF.CallStaticVoidMethodA!(env, gWindowApp, midStart, &startArgs)
if jniExceptionPending("calling WindowApp.start") { exit(2) }

// The terminate handshake parks the run loop: applicationShouldTerminate
// returning .terminateLater runs it in NSModalPanelRunLoopMode, where timers
// in .default never fire (the reply would never happen). Schedule the poll
// tick in .common so it fires through the handshake too.
let timer = Timer(timeInterval: 0.1, repeats: true) { _ in shell.tick() }
timer.tolerance = 0.02
RunLoop.main.add(timer, forMode: .common)
app.run()

// applicationShouldTerminate replied to AppKit only once the render thread
// reported EXITED — the engine and EGL are down on their own thread. Take
// WindowApp's code as ours and leave; the JVM is never destroyed.
let finalCode = Int32(jniCallInt(midExit))
print("\(SHELL): exiting with WindowApp's code \(finalCode)")
exit(finalCode)

// __JNI_AND_WINDOW__