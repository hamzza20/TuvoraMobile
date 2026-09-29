import SwiftUI
import TuvoraCore

/// IPTV Movies or Series: a row of category chips over a poster grid of the chosen category.
struct IptvLibraryScreen: View {
    let section: XtreamHubSection
    let isActive: Bool
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var hub: XtreamHubUiState?
    @State private var selectedCategory: String?
    @State private var details: PreviewBox?

    private let columns = Array(repeating: GridItem(.fixed(240), spacing: 48), count: 6)

    var body: some View {
        Group {
            if let hub, hub.accountsLoaded, hub.section == section {
                if hub.accounts.isEmpty {
                    EmptyStateView(title: "No playlists yet", message: "Add an IPTV playlist in Settings to browse its movies and series.")
                } else if let error = hub.loadError {
                    ErrorStateView(title: "This playlist didn't load", message: error.detail) { TvIptvBrowse.shared.retry() }
                } else {
                    content(hub)
                }
            } else {
                ProgressView()
            }
        }
        .onChange(of: isActive, initial: true) { _, active in
            if active { TvIptvBrowse.shared.open(section: section) }
        }
        .task {
            for await next in TvIptvBrowse.shared.state {
                hub = next
                guard next.section == section else { continue }
                if selectedCategory == nil || !next.categories.contains(where: { $0.id == selectedCategory }) {
                    selectedCategory = next.categories.first?.id
                    if let id = selectedCategory { TvIptvBrowse.shared.loadCategory(categoryId: id) }
                }
            }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback)
        }
    }

    @ViewBuilder
    private func content(_ hub: XtreamHubUiState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 16) {
                        ForEach(hub.categories, id: \.id) { category in
                            Button(category.name) {
                                selectedCategory = category.id
                                TvIptvBrowse.shared.loadCategory(categoryId: category.id)
                            }
                            .buttonStyle(ChipButtonStyle(selected: category.id == selectedCategory))
                        }
                    }
                    .padding(.vertical, 20).padding(.horizontal, 80)
                }
                .focusSection()

                if let category = hub.categories.first(where: { $0.id == selectedCategory }) {
                    if category.items.isEmpty {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        LazyVGrid(columns: columns, spacing: 56) {
                            ForEach(category.items, id: \.id) { item in
                                Button { details = PreviewBox(preview: item) } label: { PosterTile(item: item) }
                                    .buttonStyle(.card)
                                    .onAppear {
                                        if item.id == category.items.last?.id && category.hasMore {
                                            TvIptvBrowse.shared.loadMore(categoryId: category.id)
                                        }
                                    }
                            }
                        }
                        .padding(.horizontal, 80)
                        .focusSection()
                    }
                }
            }
        }
    }
}

struct PosterTile: View {
    let item: MetaPreview

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            CachedPosterArtwork(urlString: item.poster, width: 240, height: 360, maximumWidth: 480) {
                ZStack {
                    Theme.surface
                    Text(item.name).font(.callout).multilineTextAlignment(.center).padding(12).foregroundStyle(Theme.secondaryText)
                }
            }
            .frame(width: 240, height: 360)
            .clipped()
        }
    }
}

struct PreviewBox: Identifiable {
    let preview: MetaPreview
    var id: String { preview.id }
}
