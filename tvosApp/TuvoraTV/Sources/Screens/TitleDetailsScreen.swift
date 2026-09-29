import SwiftUI
import TuvoraCore

/// A movie or series: backdrop and facts, seasons and episodes for series, then the source list.
/// Picking a source resolves it (Stalker mint, debrid) and plays full screen.
struct TitleDetailsScreen: View {
    let preview: MetaPreview
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var state: MetaDetailsUiState?
    @State private var streams: StreamsUiState?
    @State private var season: Int32?
    @State private var chosenVideo: MetaVideo?
    @State private var showingSources = false
    @State private var opening = false

    private var meta: MetaDetails? { state?.meta }
    private var isSeries: Bool { (meta?.videos.count ?? 0) > 1 || preview.type == "series" }

    var body: some View {
        ZStack {
            CachedPosterArtwork(urlString: meta?.background ?? preview.banner ?? preview.poster, width: 1920, height: 1080, maximumWidth: 1920) {
                Theme.background
            }
            .overlay(LinearGradient(colors: [.black.opacity(0.95), .black.opacity(0.55), .clear], startPoint: .leading, endPoint: .trailing))
            .ignoresSafeArea()

            if let meta {
                ScrollView {
                    VStack(alignment: .leading, spacing: 28) {
                        Text(meta.name).font(.system(size: 64, weight: .bold)).lineLimit(2)
                        facts(meta)
                        if let description = meta.description_ {
                            Text(description).font(.body).foregroundStyle(Theme.secondaryText).lineLimit(5).frame(maxWidth: 1000, alignment: .leading)
                        }
                        if isSeries {
                            episodes(meta)
                        } else {
                            Button(resumeLabel(meta: meta, video: nil)) { showSources(meta: meta, video: nil) }
                                .buttonStyle(ChipButtonStyle(selected: true))
                        }
                    }
                    .padding(80)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else if let error = state?.errorMessage {
                ErrorStateView(title: "Couldn't load this title", message: error) { TvTitle.shared.load(type: preview.type, id: preview.id) }
            } else {
                ProgressView()
            }

            if showingSources, let meta {
                SourcesOverlay(streams: streams, opening: opening,
                               onPick: { stream in open(stream, meta: meta) },
                               onClose: { showingSources = false; TvTitle.shared.cancelStreams() })
            }
        }
        .task {
            TvTitle.shared.load(type: preview.type, id: preview.id)
            for await next in TvTitle.shared.details { state = next }
        }
        .task { for await next in TvTitle.shared.streams { streams = next } }
        .onExitCommand {
            if showingSources { showingSources = false; TvTitle.shared.cancelStreams() } else { dismiss() }
        }
    }

    @ViewBuilder
    private func facts(_ meta: MetaDetails) -> some View {
        HStack(spacing: 24) {
            if let year = meta.releaseInfo { Text(year) }
            if let runtime = meta.runtime { Text(runtime) }
            if let rating = meta.imdbRating { Text("IMDb \(rating)") }
            if !meta.genres.isEmpty { Text(meta.genres.prefix(3).joined(separator: " · ")) }
        }
        .font(.callout).foregroundStyle(Theme.secondaryText)
    }

    @ViewBuilder
    private func episodes(_ meta: MetaDetails) -> some View {
        let seasons = Array(Set(meta.videos.compactMap { $0.season?.int32Value })).sorted()
        let current = season ?? seasons.first(where: { $0 > 0 }) ?? seasons.first ?? 1
        VStack(alignment: .leading, spacing: 20) {
            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(seasons, id: \.self) { s in
                            Button(s == 0 ? "Specials" : "Season \(s)") { season = s }
                                .buttonStyle(ChipButtonStyle(selected: s == current))
                        }
                    }
                    .padding(.vertical, 12)
                }
                .focusSection()
            }
            LazyVStack(alignment: .leading, spacing: 10) {
                ForEach(meta.videos.filter { ($0.season?.int32Value ?? current) == current }, id: \.id) { video in
                    Button { showSources(meta: meta, video: video) } label: {
                        HStack(spacing: 20) {
                            Text("\(video.episode?.int32Value ?? 0)").font(.headline).foregroundStyle(Theme.secondaryText).frame(width: 50)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(video.title).font(.headline).lineLimit(1)
                                if let overview = video.overview { Text(overview).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(2) }
                            }
                            Spacer()
                            if TvTitle.shared.resumePositionMs(videoId: video.id, parentMetaId: meta.id, season: video.season?.int32Value ?? -1, episode: video.episode?.int32Value ?? -1) > 0 {
                                Text("Resume").font(.caption.bold()).foregroundStyle(Theme.accent)
                            }
                        }
                        .frame(maxWidth: 1200, alignment: .leading)
                    }
                    .buttonStyle(RowButtonStyle(selected: false))
                }
            }
            .focusSection()
        }
    }

    private func resumeLabel(meta: MetaDetails, video: MetaVideo?) -> String {
        let ms = TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                                 season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
        return ms > 0 ? "Resume from \(Self.clock(ms))" : "Play"
    }

    private func showSources(meta: MetaDetails, video: MetaVideo?) {
        chosenVideo = video
        showingSources = true
        TvTitle.shared.loadStreams(type: meta.type, videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                   season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
    }

    private func open(_ stream: StreamItem, meta: MetaDetails) {
        guard !opening else { return }
        opening = true
        let video = chosenVideo
        let resume = TvTitle.shared.resumePositionMs(videoId: video?.id ?? meta.id, parentMetaId: meta.id,
                                                     season: video?.season?.int32Value ?? -1, episode: video?.episode?.int32Value ?? -1)
        Task {
            defer { opening = false }
            guard let result = try? await TvTitle.shared.open(stream: stream, meta: meta, video: video, resumeMs: resume) else {
                playback.notify("This source couldn't be opened.")
                return
            }
            switch onEnum(of: result) {
            case .play(let play):
                showingSources = false
                playback.play(play.session)
            case .message(let message):
                playback.notify(message.text)
            }
        }
    }

    static func clock(_ ms: Int64) -> String {
        let total = Int(ms / 1000)
        let (h, m) = (total / 3600, (total % 3600) / 60)
        return h > 0 ? "\(h)h \(m)m" : "\(m)m"
    }
}

private struct SourcesOverlay: View {
    let streams: StreamsUiState?
    let opening: Bool
    let onPick: (StreamItem) -> Void
    let onClose: () -> Void

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("Choose a source").font(.title2).bold()
                    Spacer()
                    if streams?.isAnyLoading == true || opening { ProgressView() }
                }
                let groups = streams?.groups ?? []
                if groups.allSatisfy({ $0.streams.isEmpty }) && streams?.isAnyLoading == false {
                    Text("No sources found for this title.").foregroundStyle(Theme.secondaryText)
                    Button("Close", action: onClose)
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(groups, id: \.addonId) { group in
                                if !group.streams.isEmpty {
                                    Text(group.addonName).font(.caption.bold()).foregroundStyle(Theme.secondaryText).padding(.top, 12)
                                    ForEach(Array(group.streams.enumerated()), id: \.offset) { _, stream in
                                        Button { onPick(stream) } label: {
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text(stream.streamLabel).font(.headline).lineLimit(2)
                                                if let sub = stream.streamSubtitle { Text(sub).font(.caption).foregroundStyle(Theme.secondaryText).lineLimit(2) }
                                            }
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                        }
                                        .buttonStyle(RowButtonStyle(selected: false))
                                        .disabled(opening)
                                    }
                                }
                            }
                        }
                    }
                    .focusSection()
                }
            }
            .padding(48)
            .frame(width: 820)
            .frame(maxHeight: .infinity)
            .background(.regularMaterial)
        }
        .ignoresSafeArea()
    }
}
