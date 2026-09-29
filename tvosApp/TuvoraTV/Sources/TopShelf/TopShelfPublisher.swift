import Foundation
import TVServices
import TuvoraCore

/// Keeps the Top Shelf feed in step with Continue Watching (TvHome) and tells tvOS it changed.
@MainActor
enum TopShelfPublisher {
    private static var started = false

    static func start() {
        guard !started else { return }
        started = true
        TvHome.shared.start()
        Task { @MainActor in
            for await items in TvHome.shared.continueWatching {
                let feed = TopShelfFeed(items: items.prefix(12).map { cw in
                    TopShelfFeed.Item(
                        id: cw.parentMetaId + "|" + cw.videoId,
                        title: cw.title,
                        subtitle: TvContinueWatching.shared.episodeLabel(item: cw),
                        imageURL: cw.artwork ?? cw.background,
                        progress: Double(cw.progress),
                        deepLink: DeepLink.url(type: cw.parentMetaType, id: cw.parentMetaId, videoId: cw.seasonNumber != nil ? cw.videoId : nil).absoluteString
                    )
                })
                feed.write()
                TVTopShelfContentProvider.topShelfContentDidChange()
            }
        }
    }
}

/// tuvora://title?type=…&id=…[&video=…][&play=1]
struct DeepLink: Equatable {
    let type: String
    let id: String
    let videoId: String?
    let play: Bool

    static func url(type: String, id: String, videoId: String?) -> URL {
        var c = URLComponents()
        c.scheme = "tuvora"; c.host = "title"
        c.queryItems = [URLQueryItem(name: "type", value: type), URLQueryItem(name: "id", value: id)]
            + (videoId.map { [URLQueryItem(name: "video", value: $0)] } ?? [])
        return c.url!
    }

    init?(url: URL) {
        guard url.scheme == "tuvora", url.host == "title",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let type = items.first(where: { $0.name == "type" })?.value,
              let id = items.first(where: { $0.name == "id" })?.value else { return nil }
        self.type = type; self.id = id
        videoId = items.first(where: { $0.name == "video" })?.value
        play = items.first(where: { $0.name == "play" })?.value == "1"
    }
}

/// The deep link waiting to be opened once the app reaches its main screen.
@MainActor
final class DeepLinkCenter: ObservableObject {
    static let shared = DeepLinkCenter()
    @Published var pending: DeepLink?
}
