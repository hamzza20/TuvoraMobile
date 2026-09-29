import SwiftUI
import TuvoraCore

/// NuvioTV's details screen (ui/screens/detail/HeroSection.kt, MetaDetailsScreen.kt, EpisodesSection.kt)
/// and stream picker (ui/screens/stream/StreamScreen.kt), translated.
struct TitleDetailsScreen: View {
    let preview: MetaPreview
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var state: MetaDetailsUiState?
    @State private var season: Int32?
    @State private var sourcesFor: SourceTarget?

    private var meta: MetaDetails? { state?.meta }
    private var isSeries: Bool { preview.type == "series" || (meta?.videos.count ?? 0) > 1 }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            DetailBackdrop(url: meta?.background ?? preview.banner ?? preview.poster)

            if let meta {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: dp(24)) {
                        hero(meta).frame(minHeight: dp(540), alignment: .bottomLeading)
                        if isSeries { episodes(meta) }
                    }
                    .padding(.horizontal, dp(48))
                    .padding(.bottom, dp(48))
                }
            } else if let error = state?.errorMessage {
                NuvioStateMessage(title: "Couldn't load this title", message: error) { TvTitle.shared.load(type: preview.type, id: preview.id) }
            } else {
                ProgressView()
            }
        }
        .ignoresSafeArea()
        .task {
            TvTitle.shared.load(type: preview.type, id: preview.id)
            for await next in TvTitle.shared.details { state = next }
        }
        .fullScreenCover(item: $sourcesFor) { target in
            StreamPickerScreen(meta: target.meta, video: target.video).environmentObject(playback).environment(\.nuvio, colors)
        }
    }

    // Hero column: logo 100dp / 40% width (fallback displayMedium), Play pill + circle buttons, meta, synopsis at 60%.
    private func hero(_ meta: MetaDetails) -> some View {
        VStack(alignment: .leading, spacing: dp(16)) {
            Spacer(minLength: dp(120))
            if let logo = meta.logo, !logo.isEmpty {
                CachedPosterArtwork(urlString: logo, width: dp(384), height: dp(100), maximumWidth: dp(768)) {
                    Text(meta.name).font(NuvioType.displayMedium).foregroundStyle(colors.textPrimary)
                }
                .frame(maxWidth: dp(384), maxHeight: dp(100), alignment: .leading)
            } else {
                Text(meta.name).font(NuvioType.displayMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
            }
            HStack(spacing: dp(12)) {
                PlayPill(title: playLabel(meta)) { openSources(meta: meta, video: isSeries ? resumeEpisode(meta) : nil) }
                    .prefersDefaultFocus(true, in: namespace)
                CircleIconButton(icon: "library_add_plus") {}
            }
            .focusSection()
            metaRow(meta)
            if let description = meta.description_ {
                Text(description).font(NuvioType.bodyMedium).foregroundStyle(colors.textPrimary)
                    .lineLimit(4).frame(maxWidth: dp(560), alignment: .leading)
            }
        }
    }
    @Namespace private var namespace

    private func metaRow(_ meta: MetaDetails) -> some View {
        HStack(spacing: dp(8)) {
            let parts = [meta.genres.first, meta.releaseInfo, meta.runtime].compactMap { $0 }
            ForEach(Array(parts.enumerated()), id: \.offset) { index, part in
                if index > 0 { Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3)) }
                Text(part).font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
            }
            if let rating = meta.imdbRating {
                Circle().fill(colors.textSecondary).frame(width: dp(3), height: dp(3))
                Image("imdb_logo").resizable().scaledToFit().frame(height: dp(14))
                Text(rating).font(NuvioType.labelLarge).foregroundStyle(colors.textPrimary)
            }
        }
    }

    private func playLabel(_ meta: MetaDetails) -> String {
        let video = isSeries ? resumeEpisode(meta) : nil
        let ms = resume(meta, video)
        if ms > 0 { return video.map { "Resume S\($0.season?.int32Value ?? 0) E\($0.episode?.int32Value ?? 0)" } ?? "Resume" }
        return video.map { "Play S\($0.season?.int32Value ?? 0) E\($0.episode?.int32Value ?? 0)" } ?? "Play"
    }

    private func resumeEpisode(_ meta: MetaDetails) -> MetaVideo? {
        meta.videos.first { resume(meta, $0) > 0 } ?? meta.videos.first { ($0.season?.int32Value ?? 0) > 0 } ?? meta.videos.first
    }

    private func resume(_ meta: MetaDetails, _ video: MetaVideo?) -> Int64 {
        TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                        season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
    }

    private func openSources(meta: MetaDetails, video: MetaVideo?) {
        sourcesFor = SourceTarget(meta: meta, video: video)
    }

    // Season tabs (radius 20, padding 20×10, titleMedium) + episode cards (320×207, 2dp ring, no scale).
    private func episodes(_ meta: MetaDetails) -> some View {
        let seasons = Array(Set(meta.videos.compactMap { $0.season?.int32Value })).sorted()
        let current = season ?? seasons.first(where: { $0 > 0 }) ?? seasons.first ?? 1
        return VStack(alignment: .leading, spacing: dp(16)) {
            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: dp(12)) {
                        ForEach(seasons, id: \.self) { s in
                            SeasonTab(title: s == 0 ? "Specials" : "Season \(s)", selected: s == current) { season = s }
                        }
                    }
                    .padding(.vertical, dp(6))
                }
                .focusSection()
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: dp(16)) {
                    ForEach(meta.videos.filter { ($0.season?.int32Value ?? current) == current }, id: \.id) { video in
                        EpisodeCard(video: video, progress: episodeProgress(meta, video)) { openSources(meta: meta, video: video) }
                    }
                }
                .padding(.vertical, dp(8))
            }
            .focusSection()
        }
    }

    private func episodeProgress(_ meta: MetaDetails, _ video: MetaVideo) -> Double {
        resume(meta, video) > 0 ? 0.5 : 0
    }
}

private struct SourceTarget: Identifiable {
    let meta: MetaDetails
    let video: MetaVideo?
    var id: String { video?.id ?? meta.id }
}

/// Details backdrop: full screen, left fade across 78% and a bottom fade from 38% height, both in Background.
struct DetailBackdrop: View {
    let url: String?
    @Environment(\.nuvio) private var colors

    var body: some View {
        GeometryReader { geo in
            CachedPosterArtwork(urlString: url, width: geo.size.width, height: geo.size.height, maximumWidth: 1920) { colors.background }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .overlay(
                    LinearGradient(stops: [
                        .init(color: colors.background, location: 0), .init(color: colors.background.opacity(0.95), location: 0.1 * 0.78),
                        .init(color: colors.background.opacity(0.84), location: 0.22 * 0.78), .init(color: colors.background.opacity(0.70), location: 0.36 * 0.78),
                        .init(color: colors.background.opacity(0.52), location: 0.52 * 0.78), .init(color: colors.background.opacity(0.34), location: 0.66 * 0.78),
                        .init(color: colors.background.opacity(0.18), location: 0.78 * 0.78), .init(color: colors.background.opacity(0.07), location: 0.9 * 0.78),
                        .init(color: .clear, location: 0.78),
                    ], startPoint: .leading, endPoint: .trailing)
                )
                .overlay(
                    LinearGradient(stops: [
                        .init(color: .clear, location: 0.38), .init(color: colors.background.opacity(0.52), location: 0.7),
                        .init(color: colors.background, location: 1),
                    ], startPoint: .top, endPoint: .bottom)
                )
        }
        .ignoresSafeArea()
    }
}

/// Play: white pill (radius 32), black content, padding 24×14, 18dp icon, labelLarge; 2dp FocusRing on focus.
struct PlayPill: View {
    let title: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(8)) {
                Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                Text(title).font(NuvioType.labelLargeSemi)
            }
            .foregroundStyle(Color.black)
            .padding(.horizontal, dp(24)).padding(.vertical, dp(14))
            .background(Capsule().fill(Color.white))
            .overlay(Capsule().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

/// 48dp circle action: BackgroundCard, Secondary when focused, 22dp icon, 2dp ring.
struct CircleIconButton: View {
    let icon: String
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                .foregroundStyle(focused ? colors.onSecondary : colors.textPrimary)
                .frame(width: dp(48), height: dp(48))
                .background(Circle().fill(focused ? colors.secondary : colors.backgroundCard))
                .overlay(Circle().stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus).padding(-dp(3)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

private struct SeasonTab: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Text(title).font(NuvioType.titleMedium)
                .foregroundStyle(focused ? colors.onSecondary : colors.textPrimary)
                .padding(.horizontal, dp(20)).padding(.vertical, dp(10))
                .background(RoundedRectangle(cornerRadius: dp(20)).fill(focused ? colors.secondary : (selected ? colors.surfaceVariant : colors.backgroundCard)))
                .overlay(RoundedRectangle(cornerRadius: dp(20)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

private struct EpisodeCard: View {
    let video: MetaVideo
    let progress: Double
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                CachedPosterArtwork(urlString: video.thumbnail, width: dp(320), height: dp(207), maximumWidth: dp(640)) { colors.backgroundCard }
                    .frame(width: dp(320), height: dp(207))
                LinearGradient(stops: [.init(color: .black.opacity(0.04), location: 0.35), .init(color: .black.opacity(0.95), location: 1)],
                               startPoint: .top, endPoint: .bottom)
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text("E\(video.episode?.int32Value ?? 0)").font(NuvioType.labelMedium).foregroundStyle(colors.textSecondary)
                    Text(video.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(1)
                    if let overview = video.overview {
                        Text(overview).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(2)
                    }
                }
                .padding(dp(12))
            }
            .frame(width: dp(320), height: dp(207))
            .clipShape(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: NuvioTokens.Radius.posterCard, style: .continuous)
                .stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
            .hoverEffect(.lift)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}

// MARK: - Stream picker (StreamScreen.kt): full screen, left 40% logo, right 60% source list.

struct StreamPickerScreen: View {
    let meta: MetaDetails
    let video: MetaVideo?
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var streams: StreamsUiState?
    @State private var opening = false

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            CachedPosterArtwork(urlString: meta.background, width: 1920, height: 1080, maximumWidth: 1920) { colors.background }
                .frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
                .overlay(LinearGradient(stops: [
                    .init(color: colors.background, location: 0), .init(color: colors.background.opacity(0.85), location: 0.2),
                    .init(color: colors.background.opacity(0.40), location: 0.4), .init(color: colors.background.opacity(0.15), location: 0.5),
                    .init(color: colors.background.opacity(0.40), location: 0.6), .init(color: colors.background.opacity(0.85), location: 0.8),
                    .init(color: colors.background, location: 1),
                ], startPoint: .leading, endPoint: .trailing))
                .ignoresSafeArea()

            HStack(alignment: .top, spacing: dp(32)) {
                VStack(alignment: .leading, spacing: dp(12)) {
                    if let logo = meta.logo, !logo.isEmpty {
                        CachedPosterArtwork(urlString: logo, width: dp(300), height: dp(120), maximumWidth: dp(600)) {
                            Text(meta.name).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                        }
                        .frame(maxWidth: dp(300), maxHeight: dp(120), alignment: .leading)
                    } else {
                        Text(meta.name).font(NuvioType.headlineMedium).foregroundStyle(colors.textPrimary)
                    }
                    if let video {
                        Text("S\(video.season?.int32Value ?? 0) E\(video.episode?.int32Value ?? 0) · \(video.title)")
                            .font(NuvioType.titleMedium).foregroundStyle(colors.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .containerRelativeFrame(.horizontal) { w, _ in w * 0.4 }

                sourceList.containerRelativeFrame(.horizontal) { w, _ in w * 0.6 - dp(80) }
            }
            .padding(dp(48))
        }
        .task {
            TvTitle.shared.loadStreams(type: meta.type, videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                       season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
            for await next in TvTitle.shared.streams { streams = next }
        }
        .onDisappear { TvTitle.shared.cancelStreams() }
    }

    @ViewBuilder
    private var sourceList: some View {
        let groups = (streams?.groups ?? []).filter { !$0.streams.isEmpty }
        if groups.isEmpty {
            if streams?.isAnyLoading ?? true {
                VStack(spacing: dp(12)) { ForEach(0..<5, id: \.self) { _ in NuvioShimmer().frame(height: dp(84)) } }
            } else {
                NuvioStateMessage(title: "No sources found", message: "None of your add-ons or playlists have this title.")
            }
        } else {
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: dp(12)) {
                    ForEach(groups, id: \.addonId) { group in
                        ForEach(Array(group.streams.enumerated()), id: \.offset) { _, stream in
                            StreamCard(stream: stream) { open(stream) }
                        }
                    }
                }
                .padding(.vertical, dp(8))
            }
            .focusSection()
        }
    }

    private func open(_ stream: StreamItem) {
        guard !opening else { return }
        opening = true
        let resume = TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                                     season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
        Task {
            defer { opening = false }
            guard let result = try? await TvTitle.shared.open(stream: stream, meta: meta, video: video, resumeMs: resume) else {
                playback.notify("This source couldn't be opened."); return
            }
            switch onEnum(of: result) {
            case .play(let play):
                dismiss()
                playback.play(play.session)
            case .message(let message):
                playback.notify(message.text)
            }
        }
    }
}

/// StreamCard: BackgroundElevated, radius 12, padding 16, titleMedium name, bodySmall description,
/// addon name on the right; 2dp FocusRing on focus.
private struct StreamCard: View {
    let stream: StreamItem
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: dp(12)) {
                VStack(alignment: .leading, spacing: dp(4)) {
                    Text(stream.streamLabel).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary).lineLimit(2)
                    if let sub = stream.streamSubtitle {
                        Text(sub).font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary).lineLimit(3)
                    }
                }
                Spacer()
                Text(stream.addonName).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary).lineLimit(1)
            }
            .padding(dp(16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundElevated))
            .overlay(RoundedRectangle(cornerRadius: dp(12)).stroke(focused ? colors.focusRing : .clear, lineWidth: NuvioTokens.Stroke.focus))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }
}
