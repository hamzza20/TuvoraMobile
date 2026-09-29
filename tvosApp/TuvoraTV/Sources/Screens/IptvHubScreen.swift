import SwiftUI
import TuvoraCore

/// NuvioTV's IPTV hub (ui/screens/iptv/XtreamHubScreen.kt): a header row of Live TV / Movies / Series
/// hub chips with the playlist chip on the right, over the live guide or the category shelves.
struct IptvHubScreen: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var hub: XtreamHubUiState?
    @State private var choosingPlaylist = false
    @State private var details: PreviewBox?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let hub, hub.accountsLoaded, !hub.accounts.isEmpty {
                header(hub)
                    .padding(.top, dp(24)).padding(.horizontal, NuvioTokens.Layout.gutter)
                    .padding(.bottom, dp(16))
                    .focusSection()
                if let error = hub.loadError {
                    NuvioStateMessage(title: "Couldn't load this playlist", message: error.detail) { TvIptvBrowse.shared.retry() }
                } else if hub.section == .live {
                    LiveGuideView(accountId: hub.selectedAccountId ?? hub.accounts[0].id,
                                  categories: hub.categories.map { ($0.id, $0.name) })
                } else {
                    IptvShelves(hub: hub) { details = PreviewBox(preview: $0) }
                }
            } else if let hub, hub.accountsLoaded {
                NuvioStateMessage(title: "No playlists yet",
                                  message: "Add an IPTV playlist in Settings, or at tuvora.co on your phone or computer.")
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task {
            TvIptvBrowse.shared.open(section: hub?.section ?? .live)
            for await next in TvIptvBrowse.shared.state { hub = next }
        }
        .sheet(isPresented: $choosingPlaylist) {
            if let hub { PlaylistDialog(hub: hub) { choosingPlaylist = false } }
        }
        .fullScreenCover(item: $details) { box in
            TitleDetailsScreen(preview: box.preview).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    private func header(_ hub: XtreamHubUiState) -> some View {
        HStack(spacing: dp(8)) {
            ForEach([(XtreamHubSection.live, "Live TV"), (.movies, "Movies"), (.series, "Series")], id: \.1) { section, title in
                HubChip(title: title, selected: hub.section == section) { TvIptvBrowse.shared.open(section: section) }
            }
            Spacer()
            if let account = hub.accounts.first(where: { $0.id == hub.selectedAccountId }) ?? hub.accounts.first {
                HubChip(title: account.name, trailingIcon: hub.accounts.count > 1 ? "md_arrow_drop_down" : nil, selected: false) {
                    if hub.accounts.count > 1 { NSLog("SMOKE playlist chip pressed"); choosingPlaylist = true }
                }
            }
        }
    }
}

struct PreviewBox: Identifiable {
    let preview: MetaPreview
    var id: String { preview.id }
}

/// Provider picker: a NuvioDialog listing the playlists as SettingsActionRows.
private struct PlaylistDialog: View {
    let hub: XtreamHubUiState
    let dismiss: () -> Void

    var body: some View {
        NuvioDialog(title: "Choose a playlist") {
            ForEach(hub.accounts, id: \.id) { account in
                SettingsActionRow(title: account.name, value: account.id == hub.selectedAccountId ? "Selected" : nil) {
                    TvIptvBrowse.shared.selectPlaylist(accountId: account.id)
                    dismiss()
                }
            }
        }
    }
}

// MARK: - Movies / Series shelves

private struct IptvShelves: View {
    let hub: XtreamHubUiState
    let onOpen: (MetaPreview) -> Void

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: NuvioTokens.Layout.rowGap) {
                ForEach(hub.categories, id: \.id) { category in
                    IptvShelf(category: category, onOpen: onOpen)
                }
            }
            .padding(.vertical, dp(12))
        }
    }
}

private struct IptvShelf: View {
    let category: XtreamHubCategory
    let onOpen: (MetaPreview) -> Void

    var body: some View {
        let size = NuvioCardSize.hubPortrait
        VStack(alignment: .leading, spacing: 0) {
            NuvioShelfHeader(title: category.name) {
                if category.hasMore { SeeAllButton { TvIptvBrowse.shared.loadMore(categoryId: category.id) } }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: NuvioTokens.Layout.itemGap) {
                    if category.items.isEmpty {
                        ForEach(0..<8, id: \.self) { _ in NuvioShimmer().frame(width: size.width, height: size.height) }
                    } else {
                        ForEach(category.items, id: \.id) { item in
                            NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                            width: size.width, height: size.height) { onOpen(item) }
                                .onAppear {
                                    if item.id == category.items.last?.id && category.hasMore {
                                        TvIptvBrowse.shared.loadMore(categoryId: category.id)
                                    }
                                }
                        }
                    }
                }
                .padding(.horizontal, NuvioTokens.Layout.gutter)
                .padding(.vertical, dp(8))
            }
            .focusSection()
        }
        .onAppear { if !category.loaded && !category.loading { TvIptvBrowse.shared.loadCategory(categoryId: category.id) } }
    }
}

// MARK: - Live guide

/// XtreamLiveGuideScreen.kt: a 220dp category column (collapses once focus enters the channels), a 180dp
/// preview strip (16:9 video + info pane), the 2-hour time header, and 44dp channel rows with programme
/// cells and a 2dp now line. OK previews a channel; OK again goes full screen.
private struct LiveGuideView: View {
    let accountId: String
    let categories: [(String, String)]
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors

    @State private var channels: [LiveGuideChannel] = []
    @State private var loading = true
    @State private var category: String = "all"
    @State private var focusedChannel: LiveGuideChannel?
    @State private var previewing: LiveGuideChannel?
    @State private var previewSession: TvPlayerSession?
    @State private var programmes: [String: [XtreamProgram]] = [:]
    @State private var now = TvLiveGuide.shared.nowMs()
    @FocusState private var channelsFocused: Bool
    @State private var recents: [XtreamLiveRecent] = []

    private static let categoryWidth = dp(220), labelWidth = dp(230), rowHeight = dp(44), previewHeight = dp(180)

    private var visible: [LiveGuideChannel] {
        switch category {
        case "all": return channels
        case "recent":
            let ids = recents.map(\.contentId)
            return ids.compactMap { id in channels.first { $0.contentId == id } }
        default: return channels.filter { $0.categoryId == category }
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: dp(12)) {
            if !channelsFocused {
                categoryColumn.frame(width: Self.categoryWidth).transition(.move(edge: .leading).combined(with: .opacity))
            }
            VStack(alignment: .leading, spacing: dp(8)) {
                previewStrip.frame(height: Self.previewHeight)
                timeHeader
                channelList
            }
        }
        .padding(.leading, NuvioTokens.Layout.gutter).padding(.trailing, dp(24))
        .animation(NuvioTokens.Motion.medium, value: channelsFocused)
        .task(id: accountId) {
            loading = true
            channels = (try? await TvLiveGuide.shared.channels(accountId: accountId)) ?? []
            loading = false
        }
        .task { for await next in TvLiveGuide.shared.recents { recents = next } }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = TvLiveGuide.shared.nowMs()
            }
        }
        .onDisappear { previewSession?.close(); previewSession = nil }
    }

    // Category column: plain rows, radius 8, padding 12×8, bodyMedium; focused grey Primary, selected BackgroundElevated.
    private var categoryColumn: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: dp(2)) {
                GuideCategoryRow(title: "Recent", selected: category == "recent") { category = "recent" }
                GuideCategoryRow(title: "All channels", selected: category == "all") { category = "all" }
                ForEach(categories, id: \.0) { id, name in
                    GuideCategoryRow(title: name, selected: category == id) { category = id }
                }
            }
        }
        .focusSection()
    }

    private var previewStrip: some View {
        HStack(alignment: .top, spacing: dp(16)) {
            ZStack {
                Color.black
                if let previewSession {
                    EngineHost(session: previewSession, generation: 0)
                }
            }
            .frame(width: Self.previewHeight * 16 / 9, height: Self.previewHeight)
            .clipShape(RoundedRectangle(cornerRadius: dp(8)))

            VStack(alignment: .leading, spacing: dp(6)) {
                if let channel = focusedChannel ?? previewing {
                    Text(channel.name).font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary).lineLimit(1)
                    if let current = currentProgramme(channel) {
                        Text(current.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
                        progress(current)
                    } else {
                        Text("No information").font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                    }
                }
                Spacer()
                Text("OK preview · OK again fullscreen").font(NuvioType.labelSmall).foregroundStyle(colors.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func progress(_ programme: XtreamProgram) -> some View {
        let total = max(1, Double(programme.endMs - programme.startMs))
        let done = min(1, max(0, Double(now - programme.startMs) / total))
        let left = max(0, (programme.endMs - now) / 60_000)
        return VStack(alignment: .leading, spacing: dp(4)) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(colors.border)
                    Capsule().fill(colors.primary).frame(width: geo.size.width * done)
                }
            }
            .frame(width: dp(220), height: dp(3))
            Text("\(left) min left").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
        }
    }

    private var timeHeader: some View {
        HStack(spacing: 0) {
            Text("Today").font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                .frame(width: Self.labelWidth, alignment: .leading)
            GeometryReader { geo in
                ForEach(Array(TvGuideWindow.shared.slots(nowMs: now).enumerated()), id: \.offset) { index, slot in
                    Text(Self.clock(slot.int64Value)).font(NuvioType.labelSmall).foregroundStyle(colors.textSecondary)
                        .offset(x: geo.size.width * CGFloat(index) / 4)
                }
            }
            .frame(height: dp(16))
        }
    }

    private var channelList: some View {
        Group {
            if loading {
                VStack(spacing: dp(4)) { ForEach(0..<6, id: \.self) { _ in NuvioShimmer(cornerRadius: dp(8)).frame(height: Self.rowHeight) } }
            } else if visible.isEmpty {
                NuvioStateMessage(title: "No channels here", message: category == "recent" ? "Channels you watch show up here." : "This category is empty on your provider.")
            } else {
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: dp(4)) {
                        ForEach(Array(visible.enumerated()), id: \.element.contentId) { index, channel in
                            GuideChannelRow(number: index + 1, channel: channel, programmes: programmes[channel.contentId] ?? [],
                                            now: now, labelWidth: Self.labelWidth, height: Self.rowHeight,
                                            onFocus: { focusedChannel = channel },
                                            onSelect: { select(channel) })
                                .task {
                                    guard programmes[channel.contentId] == nil else { return }
                                    programmes[channel.contentId] = (try? await TvLiveGuide.shared.programmes(contentId: channel.contentId)) ?? []
                                }
                        }
                    }
                    .padding(.vertical, dp(4))
                }
                .focused($channelsFocused)
                .focusSection()
                .overlay(alignment: .topLeading) { nowLine }
            }
        }
    }

    private var nowLine: some View {
        GeometryReader { geo in
            let gridWidth = geo.size.width - Self.labelWidth
            Rectangle().fill(colors.error)
                .frame(width: dp(2))
                .offset(x: Self.labelWidth + gridWidth * CGFloat(TvGuideWindow.shared.nowFraction(nowMs: now)))
        }
        .allowsHitTesting(false)
    }

    private func currentProgramme(_ channel: LiveGuideChannel) -> XtreamProgram? {
        programmes[channel.contentId]?.first { $0.startMs <= now && $0.endMs > now }
    }

    /// OK previews a channel in the strip; OK on the previewing channel goes full screen.
    private func select(_ channel: LiveGuideChannel) {
        if previewing?.contentId == channel.contentId, let previewSession {
            self.previewSession = nil
            previewing = nil
            playback.play(previewSession)
            return
        }
        previewSession?.close()
        previewSession = nil
        previewing = channel
        Task {
            if let session = try? await TvIptvBrowse.shared.playChannel(contentId: channel.contentId, name: channel.name, logo: channel.logo) {
                session.attach()
                previewSession = session
            } else {
                previewing = nil
                playback.notify("\(channel.name) isn't available right now.")
            }
        }
    }

    static func clock(_ ms: Int64) -> String {
        let f = DateFormatter(); f.dateFormat = DateFormatter.dateFormat(fromTemplate: "jmm", options: 0, locale: .current)
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

private struct GuideCategoryRow: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(title).font(NuvioType.bodyMedium).lineLimit(1)
                .foregroundStyle(focused ? colors.onPrimary : (selected ? colors.textPrimary : colors.textSecondary))
                .padding(.horizontal, dp(12)).padding(.vertical, dp(8))
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.primary : (selected ? colors.backgroundElevated : .clear)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// Channel row: number (30dp), logo (30dp, radius 4), name (bodySmall) in a 230dp label block, then the
/// programme cells (radius 4, 2dp gaps; now-airing Primary 20%, others BackgroundElevated). Focused row:
/// Primary 22% fill + 2dp Primary border, radius 8.
private struct GuideChannelRow: View {
    let number: Int
    let channel: LiveGuideChannel
    let programmes: [XtreamProgram]
    let now: Int64
    let labelWidth: CGFloat
    let height: CGFloat
    let onFocus: () -> Void
    let onSelect: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 0) {
                HStack(spacing: dp(8)) {
                    Text("\(number)").font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).frame(width: dp(30), alignment: .leading)
                    CachedPosterArtwork(urlString: channel.logo, width: dp(30), height: dp(30), maximumWidth: dp(60)) { Color.clear }
                        .frame(width: dp(30), height: dp(30))
                        .clipShape(RoundedRectangle(cornerRadius: dp(4)))
                    Text(channel.name).font(NuvioType.bodySmall).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if channel.pinned {
                        Image("md_favorite").renderingMode(.template).resizable().frame(width: dp(10), height: dp(10)).foregroundStyle(colors.primary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.leading, dp(8))
                .frame(width: labelWidth, alignment: .leading)

                GeometryReader { geo in
                    ForEach(Array(programmes.enumerated()), id: \.offset) { _, programme in
                        let span = TvGuideWindow.shared.spanList(nowMs: now, startMs: programme.startMs, endMs: programme.endMs)
                        if span.count == 2 {
                            let x = geo.size.width * CGFloat(truncating: span[0])
                            let w = geo.size.width * CGFloat(truncating: span[1]) - x - dp(2)
                            let airing = programme.startMs <= now && programme.endMs > now
                            Text(programme.title).font(NuvioType.bodySmall).lineLimit(1)
                                .foregroundStyle(colors.textPrimary)
                                .padding(.horizontal, dp(6))
                                .frame(width: max(0, w), height: geo.size.height - dp(4), alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: dp(4)).fill(airing ? colors.primary.opacity(0.2) : colors.backgroundElevated))
                                .offset(x: x, y: dp(2))
                        }
                    }
                }
            }
            .frame(height: height)
            .background(RoundedRectangle(cornerRadius: dp(8)).fill(focused ? colors.primary.opacity(0.22) : .clear))
            .overlay(RoundedRectangle(cornerRadius: dp(8)).stroke(focused ? colors.primary : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
        .onChange(of: focused) { _, isFocused in if isFocused { onFocus() } }
    }
}
