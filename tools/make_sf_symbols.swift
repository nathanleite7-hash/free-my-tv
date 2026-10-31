// Renders the app's icons from Apple's SF Symbols (built into macOS) as white PNGs, with the same names as
// tools/make_symbols.py (Material Symbols), into local/assets/symbols: a folder kept out of git (SF Symbols' license
// covers Apple platforms only; this is for a personal build). Usage: swift tools/make_sf_symbols.swift [EM_PX]
import AppKit

let em = CGFloat(Int(CommandLine.arguments.dropFirst().first ?? "") ?? 172)
let out = URL(fileURLWithPath: "local/assets/symbols", isDirectory: true)
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

// name used in the app -> SF Symbol
let map: [String: String] = [
    "sunny": "sun.max.fill", "clear_night": "moon.stars.fill", "partly_cloudy_day": "cloud.sun.fill",
    "partly_cloudy_night": "cloud.moon.fill", "cloud": "cloud.fill", "foggy": "cloud.fog.fill", "rainy": "cloud.rain.fill",
    "thunderstorm": "cloud.bolt.rain.fill", "snowflake": "snowflake",
    "tv": "tv", "gamepad": "gamecontroller.fill", "hdmi": "cable.connector",
    "apps": "square.grid.3x3.fill", "settings": "gearshape.fill", "speaker": "hifispeaker.fill",
    "sparkles": "sparkles", "bedtime": "moon.fill", "home": "house.fill",
    "chevron_right": "chevron.right", "bell": "bell.fill", "sync": "arrow.triangle.2.circlepath",
    "check": "checkmark", "play": "play.fill", "tv_settings": "slider.horizontal.3",
    "chevron_down": "chevron.down", "sunrise": "sunrise.fill", "sunset": "sunset.fill",
    "live_tv": "tv.and.mediabox", "bitcoin": "bitcoinsign",
    "keystone": "skew", "cast": "airplayvideo", "bluetooth": "dot.radiowaves.left.and.right",
]

// SF Symbols draw at about the cap height of their point size: a point size of ~0.64 em gives the visual size the
// Material icons had at a 172 px em (the app scales them by the text next to them).
let config = NSImage.SymbolConfiguration(pointSize: em * 0.64, weight: .semibold)
for (name, symbol) in map {
    guard let base = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(config) else {
        print("missing:", symbol); continue
    }
    let pad: CGFloat = 2
    let size = NSSize(width: ceil(base.size.width + 2 * pad), height: ceil(base.size.height + 2 * pad))
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    // White: the symbol as a mask, filled.
    let rect = NSRect(x: pad, y: pad, width: base.size.width, height: base.size.height)
    base.draw(in: rect)
    NSColor.white.set()
    rect.insetBy(dx: -pad, dy: -pad).fill(using: .sourceAtop)
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: out.appendingPathComponent(name + ".png"))
}
print("ok:", map.count, "icons in", out.path)
