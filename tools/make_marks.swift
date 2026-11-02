// The V2 shelf's marks (each app's symbol, drawn in its color on a dark tile), as white PNGs in local/assets/marks: a
// folder kept out of git (brand marks belong to their owners; this is for a personal build). Without them the shelf
// shows each app's own logo. macOS only (it uses AppKit to render the SVGs). Usage: swift tools/make_marks.swift
import AppKit

let out = URL(fileURLWithPath: "local/assets/marks", isDirectory: true)
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

// file name -> SVG address (Simple Icons, CC0; Disney+'s wordmark from Wikimedia Commons)
let sources: [String: String] = [
    "netflix": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/netflix.svg",
    "spotify": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/spotify.svg",
    "youtube": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/youtube.svg",
    "stremio": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/stremio.svg",
    "primevideo": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/primevideo.svg",
    "hbo": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/hbo.svg",
    "apple": "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0/icons/apple.svg",
    "disneyplus": "https://upload.wikimedia.org/wikipedia/commons/3/3e/Disney%2B_logo.svg",
]

for (name, address) in sources.sorted(by: { $0.key < $1.key }) {
    var request = URLRequest(url: URL(string: address)!)
    request.setValue("FreeMyTV (personal build)", forHTTPHeaderField: "User-Agent")
    var data: Data?
    let done = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: request) { d, _, _ in data = d; done.signal() }.resume()
    done.wait()
    guard let svg = data, let image = NSImage(data: svg) else { print("couldn't get", name); continue }
    // 256 px on the long side, the shape kept; white where the mark has ink
    let scale: CGFloat = 256 / max(image.size.width, image.size.height)
    let size = NSSize(width: ceil(image.size.width * scale), height: ceil(image.size.height * scale))
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let rect = NSRect(origin: .zero, size: size)
    image.draw(in: rect)
    NSColor.white.set()
    rect.fill(using: .sourceAtop)
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: out.appendingPathComponent(name + ".png"))
    print("ok:", name)
}
