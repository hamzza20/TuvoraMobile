// Adapted from NuvioTVOS (github.com/bobsupra/NuvioTVOS, GPL-3.0 — same licence as Tuvora):
// UI/Components/PosterCard.swift — the self-contained poster image pipeline (memory + disk cache,
// decode limiter, downsampling). Copied verbatim apart from this header.
import SwiftUI
import UIKit
import ImageIO

struct ArtworkPlaceholder: View {
    let hasArtworkURL: Bool
    let cornerRadius: CGFloat

    var body: some View {
        ZStack {
            Color.clear

            if !hasArtworkURL {
                Image(systemName: "photo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 42, height: 42)
                    .foregroundColor(.white.opacity(0.38))
            }
        }
        .background(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color.white.opacity(0.07))
        )
    }
}

/// Tuvora addition: logos fit inside their box instead of filling it (`.environment(\.artworkContentMode, .fit)`).
private struct ArtworkContentModeKey: EnvironmentKey { static let defaultValue = ContentMode.fill }
extension EnvironmentValues {
    var artworkContentMode: ContentMode {
        get { self[ArtworkContentModeKey.self] }
        set { self[ArtworkContentModeKey.self] = newValue }
    }
}

struct CachedPosterArtwork<Placeholder: View>: View {
    @Environment(\.artworkContentMode) private var artworkContentMode
    let urlString: String?
    var preloadURLString: String? = nil
    let width: CGFloat
    let height: CGFloat
    var maximumWidth: CGFloat? = nil
    var preloadMaximumWidth: CGFloat? = nil
    var minimumSwapDelay: TimeInterval = 0
    var onPreloadFinished: () -> Void = {}
    @ViewBuilder let placeholder: Placeholder

    init(
        urlString: String?,
        preloadURLString: String? = nil,
        width: CGFloat,
        height: CGFloat,
        maximumWidth: CGFloat? = nil,
        preloadMaximumWidth: CGFloat? = nil,
        minimumSwapDelay: TimeInterval = 0,
        onPreloadFinished: @escaping () -> Void = {},
        @ViewBuilder placeholder: () -> Placeholder
    ) {
        self.urlString = urlString
        self.preloadURLString = preloadURLString
        self.width = width
        self.height = height
        self.maximumWidth = maximumWidth
        self.preloadMaximumWidth = preloadMaximumWidth
        self.minimumSwapDelay = minimumSwapDelay
        self.onPreloadFinished = onPreloadFinished
        self.placeholder = placeholder()
    }

    private struct CardArtworkState {
        var image: UIImage?
        var loadedKey: String?
        var previousImage: UIImage?
        var previousLoadedKey: String?
        var preloadedImage: UIImage?
        var preloadedKey: String?
    }

    @State private var state = CardArtworkState()

    private var maxPixelSize: Int {
        let displayScale = UIScreen.main.scale
        let targetMaxWidth = maximumWidth ?? width
        return max(160, Int(ceil(max(targetMaxWidth, height) * displayScale)))
    }

    private var preloadMaxPixelSize: Int {
        let displayScale = UIScreen.main.scale
        let targetMaxWidth = preloadMaximumWidth ?? maximumWidth ?? width
        return max(160, Int(ceil(max(targetMaxWidth, height) * displayScale)))
    }

    private var cacheKey: String {
        "\(urlString ?? "")#\(maxPixelSize)"
    }

    private var preloadCacheKey: String {
        "\(preloadURLString ?? "")#\(preloadMaxPixelSize)"
    }

    var body: some View {
        ZStack(alignment: .center) {
            if let image = displayedImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: artworkContentMode)
                    .frame(width: width, height: height, alignment: artworkContentMode == .fit ? .bottomLeading : .center)
                    .clipped()
            } else {
                placeholder
            }
        }
        .task(id: cacheKey) {
            await load()
        }
        .task(id: preloadCacheKey) {
            await preload()
        }
    }

    private var displayedImage: UIImage? {
        if state.loadedKey == cacheKey { return state.image }
        if state.preloadedKey == cacheKey { return state.preloadedImage }
        if state.previousLoadedKey == cacheKey { return state.previousImage }

        // While a brand-new variant is loading, keep real artwork on screen.
        // For landscape expansion this naturally starts with a zoomed poster;
        // for collapse the matching portrait is normally `previousImage`.
        return state.image ?? state.previousImage
    }

    @MainActor
    private func load() async {
        guard let urlString,
              let url = URL(string: urlString) else {
            state.image = nil
            state.loadedKey = nil
            state.previousImage = nil
            state.previousLoadedKey = nil
            return
        }

        let key = cacheKey
        let traceLoad = preloadURLString != nil
        let started = TVHomeDebugTrace.now()
        if traceLoad {
            TVHomeDebugTrace.log("art.load.begin host=\(url.host ?? "unknown") key=\(key)")
        }
        let loadStartedAt = Date()
        if state.loadedKey == key { return }

        if state.preloadedKey == key, let preloadedImage = state.preloadedImage {
            state.previousImage = state.image
            state.previousLoadedKey = state.loadedKey
            state.image = preloadedImage
            state.loadedKey = key
            if traceLoad {
                TVHomeDebugTrace.log(
                    "art.load.end source=preloaded ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
                )
            }
            return
        }

        // Moving back from landscape to portrait should be synchronous. The
        // portrait was retained when the landscape artwork replaced it, so
        // promote it without waiting for even an in-memory actor lookup.
        if state.previousLoadedKey == key, let previousImage = state.previousImage {
            state.image = previousImage
            state.loadedKey = key
            state.previousImage = nil
            state.previousLoadedKey = nil
            if traceLoad {
                TVHomeDebugTrace.log(
                    "art.load.end source=previous ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
                )
            }
            return
        }

        if let cached = await PosterArtworkCache.shared.image(for: url, maxPixelSize: maxPixelSize) {
            let remainingDelay = minimumSwapDelay - Date().timeIntervalSince(loadStartedAt)
            if remainingDelay > 0 {
                try? await Task.sleep(nanoseconds: UInt64(remainingDelay * 1_000_000_000))
            }
            guard !Task.isCancelled, key == cacheKey else { return }
            state.previousImage = state.image
            state.previousLoadedKey = state.loadedKey
            state.image = cached
            state.loadedKey = key
        }
        if traceLoad {
            TVHomeDebugTrace.log(
                "art.load.end source=cache ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
            )
        }
    }

    @MainActor
    private func preload() async {
        guard let preloadURLString,
              let url = URL(string: preloadURLString) else {
            return
        }

        let key = preloadCacheKey
        let started = TVHomeDebugTrace.now()
        TVHomeDebugTrace.log(
            "art.preload.begin host=\(url.host ?? "unknown") key=\(key)"
        )
        if state.loadedKey == key, let image = state.image {
            state.preloadedImage = image
            state.preloadedKey = key
            onPreloadFinished()
            TVHomeDebugTrace.log(
                "art.preload.end source=loaded ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
            )
            return
        }
        if state.preloadedKey == key {
            onPreloadFinished()
            TVHomeDebugTrace.log(
                "art.preload.end source=preloaded ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
            )
            return
        }

        let cached = await PosterArtworkCache.shared.image(
            for: url,
            maxPixelSize: preloadMaxPixelSize
        )
        if let cached {
            guard !Task.isCancelled, key == preloadCacheKey else { return }
            state.preloadedImage = cached
            state.preloadedKey = key
        }
        onPreloadFinished()
        TVHomeDebugTrace.log(
            "art.preload.end source=cache hit=\(cached != nil) "
                + "ms=\(TVHomeDebugTrace.elapsedMilliseconds(since: started))"
        )
    }
}

actor PosterArtworkCache {
    static let shared = PosterArtworkCache()
    private static let tracker = NSCacheMemoryTracker(
        maxCost: {
            let gib = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824.0
            if gib > 3.5 {
                return 140 * 1024 * 1024 // 140 MB (Apple TV 4K Gen 2/3)
            } else if gib > 2.5 {
                return 100 * 1024 * 1024 // 100 MB (Apple TV 4K Gen 1)
            } else {
                return 60 * 1024 * 1024  // 60 MB (Apple TV HD)
            }
        }()
    )

    static func telemetryMetrics() -> (count: Int, totalBytes: Int, maxCost: Int) {
        tracker.metrics()
    }

    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    init() {
        let gib = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824.0
        if gib > 3.5 {
            cache.countLimit = 220
            cache.totalCostLimit = Self.tracker.maxCost
        } else if gib > 2.5 {
            cache.countLimit = 160
            cache.totalCostLimit = Self.tracker.maxCost
        } else {
            cache.countLimit = 90
            cache.totalCostLimit = Self.tracker.maxCost
        }
        cache.delegate = Self.tracker
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: nil
        ) { _ in
            Task {
                await PosterArtworkCache.shared.purge()
            }
        }
        #endif
    }

    func purge() {
        cache.removeAllObjects()
        Self.tracker.reset()
    }

    static func clearAllArtwork() async {
        await shared.purge()
        await PosterDiskCache.shared.clear()
        posterURLSession.configuration.urlCache?.removeAllCachedResponses()
        URLCache.shared.removeAllCachedResponses()
    }

    func updateMemoryCache(_ image: UIImage, forKey key: NSString) {
        let cost = image.decodedByteCost
        cache.setObject(image, forKey: key, cost: cost)
        Self.tracker.recordInsertion(cost: cost)
    }

    func image(for url: URL, maxPixelSize: Int) async -> UIImage? {
        let boundedPixelSize = min(max(maxPixelSize, 160), 1400)
        let key = "\(url.absoluteString)#\(boundedPixelSize)" as NSString

        if let cached = cache.object(forKey: key) {
            return cached
        }

        if let task = inFlight[key as String] {
            return await task.value
        }

        let isVolatile = PosterArtworkCachePolicy.isVolatile(url)

        let task = Task.detached(priority: .utility) { () -> UIImage? in
            // Disk before network (Stale-While-Revalidate). The bytes are keyed by URL alone,
            // so one stored poster serves every size a card asks for.
            if let stored = await PosterDiskCache.shared.data(for: url),
               let image = await PosterDecodeLimiter.shared.image(
                   from: stored.data,
                   maxPixelSize: boundedPixelSize
               ) {
                // If it's a dynamic/volatile rating poster and the disk cache is stale (> 24h),
                // silently revalidate in the background to refresh rating badges without blocking UI.
                if isVolatile && !stored.isFresh {
                    Task.detached(priority: .background) {
                        guard let freshData = await downloadPosterData(url: url) else { return }
                        await PosterDiskCache.shared.store(freshData, for: url)
                        if let freshImage = await PosterDecodeLimiter.shared.image(
                            from: freshData,
                            maxPixelSize: boundedPixelSize
                        ) {
                            await PosterArtworkCache.shared.updateMemoryCache(freshImage, forKey: key)
                        }
                    }
                }
                return image
            }

            guard let data = await downloadPosterData(url: url) else { return nil }
            await PosterDiskCache.shared.store(data, for: url)
            return await PosterDecodeLimiter.shared.image(
                from: data,
                maxPixelSize: boundedPixelSize
            )
        }

        inFlight[key as String] = task
        let image = await task.value
        inFlight[key as String] = nil

        if let image {
            let cost = image.decodedByteCost
            cache.setObject(image, forKey: key, cost: cost)
            Self.tracker.recordInsertion(cost: cost)
        }
        return image
    }
}

/// Coil naturally keeps decode work bounded. Match that behavior so mounting a
/// newly visible Home shelf cannot fan out into a burst of AppleJPEG workers.
private actor PosterDecodeLimiter {
    static let shared = PosterDecodeLimiter(maxConcurrentDecodes: 3)

    private let maxConcurrentDecodes: Int
    private var activeDecodes = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(maxConcurrentDecodes: Int) {
        self.maxConcurrentDecodes = max(1, maxConcurrentDecodes)
    }

    func image(from data: Data, maxPixelSize: Int) async -> UIImage? {
        await acquire()
        defer { release() }

        guard !Task.isCancelled else { return nil }
        return await Task.detached(priority: .utility) {
            downsamplePosterImage(data: data, maxPixelSize: maxPixelSize)
        }.value
    }

    private func acquire() async {
        if activeDecodes < maxConcurrentDecodes {
            activeDecodes += 1
            return
        }

        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    private func release() {
        if waiters.isEmpty {
            activeDecodes -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

/// Poster bytes that survive relaunch, mirroring the Android image loader's
/// 200 MB Coil disk cache.
///
/// tvOS gives `URLCache` no disk store, so without this every cold start
/// re-requests every poster. Against an add-on that renders art on demand that
/// also re-triggers every slow generation, which is why the same Home looks
/// worse on Apple TV than on Android for identical add-ons. Stored raw and
/// keyed by URL alone — decoding happens per card, at that card's size.
actor PosterDiskCache {
    static let shared = PosterDiskCache()

    private let directory: URL
    private let maximumBytes = 200 * 1024 * 1024
    private let fileManager = FileManager.default
    /// Walking the directory on every write would cost more than the eviction
    /// saves, so the sweep runs once per batch of new artwork.
    private var bytesWrittenSinceTrim = 0
    private let trimInterval = 20 * 1024 * 1024
    static let freshnessTTL: TimeInterval = 24 * 60 * 60
    /// Refresh artwork cached by releases that treated generated poster bytes
    /// as immutable. Future freshness is governed by `freshnessTTL`.
    private static let storageVersion = "v2"

    init() {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = caches.appendingPathComponent("poster_artwork", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func data(for url: URL) -> (data: Data, isFresh: Bool)? {
        let file = fileURL(for: url)
        guard let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
            return nil
        }
        guard let attributes = try? fileManager.attributesOfItem(atPath: file.path),
              let modified = attributes[.modificationDate] as? Date else {
            return (data, false)
        }
        let fresh = PosterDiskCacheFreshness.isFresh(modified: modified, now: Date(), ttl: Self.freshnessTTL)
        return (data, fresh)
    }

    func store(_ data: Data, for url: URL) {
        try? data.write(to: fileURL(for: url), options: .atomic)

        bytesWrittenSinceTrim += data.count
        guard bytesWrittenSinceTrim >= trimInterval else { return }
        bytesWrittenSinceTrim = 0
        trim()
    }

    private func fileURL(for url: URL) -> URL {
        // A poster URL can carry query parameters and characters a file name
        // cannot, so hash it rather than sanitising it.
        let digest = SHA256.hash(data: Data("\(Self.storageVersion):\(url.absoluteString)".utf8))
        return directory.appendingPathComponent(digest.map { String(format: "%02x", $0) }.joined())
    }

    private func trim() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: keys
        ) else {
            return
        }

        var entries: [(url: URL, modified: Date, size: Int)] = []
        var total = 0
        for file in files {
            guard let values = try? file.resourceValues(forKeys: Set(keys)),
                  let size = values.fileSize else { continue }
            entries.append((file, values.contentModificationDate ?? .distantPast, size))
            total += size
        }

        guard total > maximumBytes else { return }
        for entry in entries.sorted(by: { $0.modified < $1.modified }) {
            guard total > maximumBytes else { break }
            try? fileManager.removeItem(at: entry.url)
            total -= entry.size
        }
    }

    func clear() {
        guard let files = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        ) else { return }
        for file in files {
            try? fileManager.removeItem(at: file)
        }
        bytesWrittenSinceTrim = 0
    }
}

/// Pure freshness rule for deterministic boundary tests.
enum PosterDiskCacheFreshness {
    static func isFresh(modified: Date, now: Date, ttl: TimeInterval) -> Bool {
        let age = now.timeIntervalSince(modified)
        return age >= 0 && age <= ttl
    }
}

private let posterURLSession: URLSession = {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 10
    config.timeoutIntervalForResource = 20
    config.httpMaximumConnectionsPerHost = 10
    let totalRam = ProcessInfo.processInfo.physicalMemory
    let isLegacyDevice = totalRam <= 2_500_000_000 // <= 2.5 GB (Apple TV HD)
    config.urlCache = URLCache(
        memoryCapacity: isLegacyDevice ? (8 * 1024 * 1024) : (20 * 1024 * 1024),
        diskCapacity: isLegacyDevice ? (50 * 1024 * 1024) : (100 * 1024 * 1024),
        diskPath: "nuvio_poster_urlcache"
    )
    return URLSession(configuration: config)
}()

/// Matches what the Android loader gets from OkHttp's defaults: a 10s ceiling
/// instead of `URLSession`'s 60s, and a non-2xx response treated as a failure
/// instead of being handed to the decoder as if it were image bytes.
enum PosterArtworkCachePolicy {
    private static let volatileHosts = [
        "xperience-app.com", "btttr.cc", "ratingposterdb.com", "top-posters.com",
        "easyratingsdb.com", "extendedratings.com", "postersplus.elfhosted.com"
    ]

    static func isVolatile(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return volatileHosts.contains { host == $0 || host.hasSuffix(".\($0)") }
    }
}

private func downloadPosterData(url: URL, revalidate: Bool = false) async -> Data? {
    var request = URLRequest(url: url)
    request.timeoutInterval = 10
    if revalidate { request.cachePolicy = .reloadIgnoringLocalCacheData }

    guard let (data, response) = try? await posterURLSession.data(for: request) else {
        return nil
    }
    if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
        return nil
    }
    return data.isEmpty ? nil : data
}

private func downsamplePosterImage(data: Data, maxPixelSize: Int) -> UIImage? {
    let sourceOptions: [CFString: Any] = [
        kCGImageSourceShouldCache: false
    ]
    guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions as CFDictionary) else {
        return UIImage(data: data)
    }

    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
    ]

    guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
        return UIImage(data: data)
    }
    return UIImage(cgImage: cgImage)
}
// MARK: - Memory Trackers

/// Thread-safe tracker and delegate for `NSCache` instances to maintain accurate
/// counts and decoded byte costs without blocking or actor isolation locks.
public final class NSCacheMemoryTracker: NSObject, NSCacheDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var trackedCount: Int = 0
    private var trackedBytes: Int = 0
    public let maxCost: Int

    public init(maxCost: Int = 0) {
        self.maxCost = maxCost
        super.init()
    }

    public func recordInsertion(cost: Int) {
        lock.lock()
        trackedCount += 1
        trackedBytes += cost
        lock.unlock()
    }

    public func cache(_ cache: NSCache<AnyObject, AnyObject>, willEvictObject obj: Any) {
        let cost = (obj as? UIImage)?.decodedByteCost ?? 0
        lock.lock()
        trackedCount = max(0, trackedCount - 1)
        trackedBytes = max(0, trackedBytes - cost)
        lock.unlock()
    }

    public func reset() {
        lock.lock()
        trackedCount = 0
        trackedBytes = 0
        lock.unlock()
    }

    public func metrics() -> (count: Int, totalBytes: Int, maxCost: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (trackedCount, trackedBytes, maxCost)
    }
}

// MARK: - Tuvora shims for the adapted pipeline

import CryptoKit

/// NuvioTVOS's home trace, reduced to what the image pipeline calls (debug-only logging).
enum TVHomeDebugTrace {
    static func now() -> CFAbsoluteTime { CFAbsoluteTimeGetCurrent() }
    static func elapsedMilliseconds(since start: CFAbsoluteTime) -> Int { Int((CFAbsoluteTimeGetCurrent() - start) * 1000) }
    static func log(_ message: @autoclosure () -> String) {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-traceArtwork") { NSLog("[art] %@", message()) }
        #endif
    }
}

extension UIImage {
    /// Decoded bitmap size, the NSCache cost unit (from NuvioTVOS PlaybackEngineControlling.swift).
    var decodedByteCost: Int {
        guard let cgImage else { return 0 }
        return cgImage.bytesPerRow * cgImage.height
    }
}
