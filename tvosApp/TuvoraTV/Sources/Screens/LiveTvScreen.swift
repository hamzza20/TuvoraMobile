import SwiftUI
import TuvoraCore

/// Live TV: categories on the left, the category's channels with now/next on the right.
/// Select a channel to play it full screen.
struct LiveTvScreen: View {
    let isActive: Bool
    @EnvironmentObject private var playback: PlaybackCoordinator
    @State private var hub: XtreamHubUiState?
    @State private var epg: [String: ChannelEpg] = [:]
    @State private var selectedCategory: String?
    @State private var opening: String?

    var body: some View {
        Group {
            if let hub, hub.accountsLoaded, hub.section == .live {
                if hub.accounts.isEmpty {
                    EmptyStateView(title: "No playlists yet",
                                   message: "Add an IPTV playlist in Settings, or at tuvora.co on your phone or computer.")
                } else {
                    content(hub)
                }
            } else {
                ProgressView()
            }
        }
        .onChange(of: isActive, initial: true) { _, active in
            if active { TvIptvBrowse.shared.open(section: .live) }
        }
        .task {
            for await next in TvIptvBrowse.shared.state {
                hub = next
                guard next.section == .live else { continue }
                if selectedCategory == nil || !next.categories.contains(where: { $0.id == selectedCategory }) {
                    selectedCategory = next.categories.first?.id
                    if let id = selectedCategory { TvIptvBrowse.shared.loadCategory(categoryId: id) }
                }
            }
        }
        .task {
            for await next in TvIptvBrowse.shared.epg { epg = next }
        }
    }

    @ViewBuilder
    private func content(_ hub: XtreamHubUiState) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            if hub.accounts.count > 1 {
                PlaylistPicker(accounts: hub.accounts, selectedId: hub.selectedAccountId)
            }
            if let error = hub.loadError {
                ErrorStateView(title: "This playlist didn't load", message: error.detail) { TvIptvBrowse.shared.retry() }
            } else if hub.loadingCategories && hub.categories.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                HStack(alignment: .top, spacing: 40) {
                    CategoryList(categories: hub.categories, selected: $selectedCategory)
                        .frame(width: 440)
                    ChannelList(category: hub.categories.first { $0.id == selectedCategory }, epg: epg, opening: opening) { channel in
                        open(channel)
                    }
                }
            }
        }
        .padding(.horizontal, 80)
    }

    private func open(_ channel: MetaPreview) {
        guard opening == nil else { return }
        opening = channel.id
        Task {
            defer { opening = nil }
            if TvIptvBrowse.shared.isOffline {
                playback.notify("You're offline. Live TV needs an internet connection.")
                return
            }
            if let session = try? await TvIptvBrowse.shared.playChannel(contentId: channel.id, name: channel.name, logo: channel.logo) {
                playback.play(session)
            } else {
                playback.notify("\(channel.name) isn't available right now.")
            }
        }
    }
}

private struct PlaylistPicker: View {
    let accounts: [XtreamAccount]
    let selectedId: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(accounts, id: \.id) { account in
                    Button(account.name) { TvIptvBrowse.shared.selectPlaylist(accountId: account.id) }
                        .buttonStyle(ChipButtonStyle(selected: account.id == selectedId))
                }
            }
            .padding(.vertical, 12)
        }
    }
}

private struct CategoryList: View {
    let categories: [XtreamHubCategory]
    @Binding var selected: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(categories, id: \.id) { category in
                    Button {
                        selected = category.id
                        TvIptvBrowse.shared.loadCategory(categoryId: category.id)
                    } label: {
                        Text(category.name).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(RowButtonStyle(selected: category.id == selected))
                }
            }
            .padding(.vertical, 20)
        }
        .focusSection()
    }
}

private struct ChannelList: View {
    let category: XtreamHubCategory?
    let epg: [String: ChannelEpg]
    let opening: String?
    let onSelect: (MetaPreview) -> Void

    var body: some View {
        if let category {
            if category.items.isEmpty && (category.loading || !category.loaded) {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if category.items.isEmpty {
                EmptyStateView(title: "No channels here", message: "This category is empty on your provider.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(category.items, id: \.id) { channel in
                            Button { onSelect(channel) } label: {
                                ChannelRow(channel: channel, epg: epg[channel.id], opening: opening == channel.id)
                            }
                            .buttonStyle(RowButtonStyle(selected: false))
                            .onAppear {
                                TvIptvBrowse.shared.ensureEpg(contentId: channel.id)
                                if channel.id == category.items.last?.id && category.hasMore {
                                    TvIptvBrowse.shared.loadMore(categoryId: category.id)
                                }
                            }
                        }
                    }
                    .padding(.vertical, 20)
                }
                .focusSection()
            }
        } else {
            Spacer()
        }
    }
}

private struct ChannelRow: View {
    let channel: MetaPreview
    let epg: ChannelEpg?
    let opening: Bool

    var body: some View {
        HStack(spacing: 24) {
            CachedPosterArtwork(urlString: channel.logo ?? channel.poster, width: 120, height: 68, maximumWidth: 240) {
                RoundedRectangle(cornerRadius: 8).fill(Theme.surface)
                    .overlay(Text(String(channel.name.prefix(2))).font(.headline).foregroundStyle(Theme.secondaryText))
            }
            .frame(width: 120, height: 68)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 6) {
                Text(channel.name).font(.headline).lineLimit(1)
                if let now = epg?.now {
                    Text(now).font(.callout).foregroundStyle(Theme.secondaryText).lineLimit(1)
                }
                if let next = epg?.next {
                    Text("Next: \(next)").font(.caption).foregroundStyle(Theme.secondaryText.opacity(0.8)).lineLimit(1)
                }
            }
            Spacer()
            if opening { ProgressView() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
