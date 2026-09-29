import Foundation

/// The Continue Watching row the Top Shelf extension shows, written by the app into the shared App Group
/// (tvOS gives an app group only Library/Caches — purgeable, and the app rewrites it on every change).
struct TopShelfFeed: Codable {
    struct Item: Codable {
        let id: String
        let title: String
        let subtitle: String?
        let imageURL: String?
        let progress: Double
        /// tuvora://title?type=…&id=…&video=… — opens the title (Select) or resumes it (Play/Pause).
        let deepLink: String
    }
    let items: [Item]

    static let appGroup = "group.com.tuvora.media"

    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("Library/Caches", isDirectory: true)
            .appendingPathComponent("topshelf.json")
    }

    static func read() -> TopShelfFeed? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(TopShelfFeed.self, from: data)
    }

    func write() {
        guard let url = Self.fileURL, let data = try? JSONEncoder().encode(self) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }
}
