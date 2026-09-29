import Foundation
import TVServices

/// Tuvora's Top Shelf: the viewer's Continue Watching as a sectioned row of 16:9 cards with progress
/// (HIG › Top Shelf). The app writes the feed; this extension only reads it.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        guard let feed = TopShelfFeed.read(), !feed.items.isEmpty else { completionHandler(nil); return }
        let items: [TVTopShelfSectionedItem] = feed.items.map { entry in
            let item = TVTopShelfSectionedItem(identifier: entry.id)
            item.title = entry.subtitle.map { "\(entry.title) · \($0)" } ?? entry.title
            item.imageShape = .hdtv
            if let string = entry.imageURL, let url = URL(string: string) {
                item.setImageURL(url, for: .screenScale1x)
                item.setImageURL(url, for: .screenScale2x)
            }
            item.playbackProgress = entry.progress
            if let url = URL(string: entry.deepLink) {
                item.displayAction = TVTopShelfAction(url: url)
                item.playAction = TVTopShelfAction(url: url.appendingQuery("play", "1"))
            }
            return item
        }
        let section = TVTopShelfItemCollection(items: items)
        section.title = "Continue Watching"
        completionHandler(TVTopShelfSectionedContent(sections: [section]))
    }
}

private extension URL {
    func appendingQuery(_ name: String, _ value: String) -> URL {
        var components = URLComponents(url: self, resolvingAgainstBaseURL: false)
        let existing = components?.queryItems ?? []
        components?.queryItems = existing + [URLQueryItem(name: name, value: value)]
        return components?.url ?? self
    }
}
