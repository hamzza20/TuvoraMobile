import SwiftUI
import TuvoraCore
import UIKit

/// Registers both playback engines with the shared session (PlaybackLanePolicy picks per stream).
enum PlayerEngines {
    static func register() {
        TvPlayerEngines.shared.register(lane: .libmpv, creator: Creator { MPVPlayerBridgeImpl() })
        TvPlayerEngines.shared.register(lane: .avPlayer, creator: Creator { AVPlayerBridgeImpl() })
    }

    private final class Creator: NSObject, NuvioPlayerBridgeCreator {
        let make: () -> NuvioPlayerBridge
        init(_ make: @escaping () -> NuvioPlayerBridge) { self.make = make }
        func createBridge() -> NuvioPlayerBridge { make() }
    }
}

/// Full-screen playback over either engine: NuvioTV's player controls (PlayerScreen.kt:1736-2365) with
/// Apple TV remote behaviour (as AVPlayerViewController): click / Play-Pause toggles, left/right skip
/// 10 s, a swipe on the touch surface scrubs (click commits, Menu cancels), swipe down opens the
/// audio/subtitle panel, Menu hides the controls or leaves.
struct TvPlayerScreen: View {
    let session: TvPlayerSession
    let onClose: () -> Void

    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var state: TvPlayerState?
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubMs: Int64?          // non-nil while scrubbing: the previewed position
    @State private var scrubOriginMs: Int64 = 0
    @State private var panel: PlayerPanel?
    @State private var skipFlash: String?
    @FocusState private var rootFocused: Bool

    private var isLive: Bool { state?.isLive ?? false }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            EngineHost(session: session, generation: Int(state?.engineGeneration ?? 0)).ignoresSafeArea()

            if let state {
                PlayerChrome(session: session, state: state, scrubMs: scrubMs, visible: controlsVisible || scrubMs != nil,
                             skipFlash: skipFlash,
                             onShowPanel: { panel = $0 }, onActivity: bumpControls)
                if state.showNextEpisode, let next = state.nextEpisode, panel == nil, let meta = session.seriesMeta {
                    NextEpisodeCard(video: next) { playback.openSources(meta: meta, video: next) }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .padding(.trailing, dp(32)).padding(.bottom, controlsVisible ? dp(150) : dp(32))
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
                if let panel {
                    // HIG › Materials: glass over bright video needs ~35% dimming beneath it to stay legible.
                    Color.black.opacity(0.35).ignoresSafeArea().transition(.opacity)
                    if panel == .episodes || panel == .sources {
                        ContentPanel(session: session, kind: panel) { self.panel = nil; bumpControls() }
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    } else {
                        TrackPanel(session: session, kind: panel) { self.panel = nil; bumpControls() }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            RemoteTouchCatcher(
                isActive: { panel == nil && !isLive },
                onBegan: { scrubOriginMs = scrubMs ?? state?.positionMs ?? 0 },
                onMoved: { dx, _ in scrub(dx: dx) },
                onEnded: { _, _ in }
            )
            .allowsHitTesting(false)
        }
        .focusable(panel == nil && !controlsVisible)
        .focused($rootFocused)
        .onAppear {
            session.attach()
            bumpControls()
            // Simulator smoke hook: `-smokePanel subtitles|audio|aspect` opens the track panel after 5 s.
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-smokePanel"), i + 1 < args.count {
                let kind: PlayerPanel = args[i + 1] == "audio" ? .audio : (args[i + 1] == "aspect" ? .aspect : .subtitles)
                Task { try? await Task.sleep(nanoseconds: 5_000_000_000); panel = kind }
            }
        }
        .onDisappear { session.detach() }
        .onPlayPauseCommand { commitScrubOrToggle() }
        .onTapGesture { commitScrubOrToggle() }
        .onMoveCommand { direction in
            guard panel == nil else { return }
            switch direction {
            case .left where !controlsVisible: skip(-10_000)
            case .right where !controlsVisible: skip(10_000)
            case .up where !controlsVisible && isLive && playback.zapper != nil: zap(-1)
            case .down where !controlsVisible && isLive && playback.zapper != nil: zap(1)
            case .down where !controlsVisible: withAnimation(NuvioTokens.Motion.overlay) { panel = .subtitles }
            case .up where !controlsVisible: bumpControls()
            default: bumpControls()
            }
        }
        .onExitCommand {
            if panel != nil { withAnimation { panel = nil }; return }
            if scrubMs != nil { scrubMs = nil; return }                 // Menu cancels a scrub
            if controlsVisible && state?.isPlaying == true { withAnimation(NuvioTokens.Motion.overlay) { controlsVisible = false }; rootFocused = true; return }
            session.close()
            onClose()
        }
        .animation(NuvioTokens.Motion.overlay, value: controlsVisible)
        .animation(NuvioTokens.Motion.overlay, value: panel)
        .task {
            for await next in session.state {
                state = next
                NSLog("SMOKE player lane=%@ gen=%d loading=%d playing=%d pos=%lld dur=%lld err=%@",
                      "\(next.lane)", next.engineGeneration, next.isLoading, next.isPlaying,
                      next.positionMs, next.durationMs, next.errorMessage ?? "-")
            }
        }
    }

    /// Live zapping: the next/previous channel of the list the viewer started from.
    private func zap(_ offset: Int) {
        guard let zapper = playback.zapper else { return }
        Task {
            if let next = await zapper(offset) {
                playback.play(next, zapper: zapper)
            }
        }
    }

    private func commitScrubOrToggle() {
        if let target = scrubMs {
            session.seekTo(positionMs: target)
            scrubMs = nil
            session.play()
        } else if state?.errorMessage != nil {
            session.retry()
        } else if !controlsVisible {
            session.togglePlayPause()
        }
        bumpControls()
    }

    /// Touch-surface scrub: one full swipe moves about 1/4 of the title (minimum 0.05 s per point).
    private func scrub(dx: CGFloat) {
        guard let state, state.durationMs > 0 else { return }
        if scrubMs == nil { session.pause() }
        let msPerPoint = max(50.0, Double(state.durationMs) / (1920.0 * 4))
        scrubMs = min(state.durationMs, max(0, scrubOriginMs + Int64(Double(dx) * msPerPoint)))
        bumpControls()
    }

    private func skip(_ deltaMs: Int64) {
        guard !isLive else { return }
        session.seekBy(offsetMs: deltaMs)
        skipFlash = deltaMs > 0 ? "+10s" : "−10s"
        Task { try? await Task.sleep(nanoseconds: 900_000_000); skipFlash = nil }
        bumpControls()
    }

    private func bumpControls() {
        controlsVisible = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled, state?.isPlaying == true, panel == nil, scrubMs == nil {
                controlsVisible = false
                rootFocused = true
            }
        }
    }
}

enum PlayerPanel: Hashable { case subtitles, audio, aspect, episodes, sources }

/// Hosts the current engine's view controller; swaps it when the session escalates engines.
struct EngineHost: UIViewControllerRepresentable {
    let session: TvPlayerSession
    let generation: Int

    func makeUIViewController(context: Context) -> EngineContainer { EngineContainer() }

    func updateUIViewController(_ container: EngineContainer, context: Context) {
        container.show(generation: generation) { session.viewController() }
    }
}

final class EngineContainer: UIViewController {
    private var shownGeneration = -1
    private var child: UIViewController?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }

    func show(generation: Int, make: () -> UIViewController?) {
        guard generation != shownGeneration, let next = make() else { return }
        shownGeneration = generation
        if let child {
            child.willMove(toParent: nil)
            child.view.removeFromSuperview()
            child.removeFromParent()
        }
        addChild(next)
        next.view.frame = view.bounds
        next.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(next.view)
        next.didMove(toParent: self)
        child = next
    }
}

/// NuvioTV's player chrome: top gradient 150dp (black 70%→0), bottom 200dp (0→black 80%), bottom block
/// padding 32×24 — title (headlineMedium), episode line (titleMedium 90%), year · via (bodyMedium 68%) —
/// then the seek bar (8dp, 12 focused; track white 30/45%, buffered Secondary 35%, played Secondary)
/// and the 48dp circle buttons (focused: white fill, black icon), in glass on tvOS 26.
private struct PlayerChrome: View {
    let session: TvPlayerSession
    let state: TvPlayerState
    let scrubMs: Int64?
    let visible: Bool
    let skipFlash: String?
    let onShowPanel: (PlayerPanel) -> Void
    let onActivity: () -> Void
    @Environment(\.nuvio) private var colors

    var body: some View {
        ZStack {
            if let error = state.errorMessage {
                VStack(spacing: dp(10)) {
                    Text("Playback failed").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                    Text(error).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).multilineTextAlignment(.center)
                    Text("Press the touch surface to try again").font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
                }
                .padding(dp(24))
                .navigationGlass(in: RoundedRectangle(cornerRadius: NuvioTokens.Radius.dialog))
            } else if state.isLoading && scrubMs == nil {
                ProgressView().scaleEffect(1.4)
            }
            if let skipFlash {
                Text(skipFlash).font(NuvioType.headlineMedium).foregroundStyle(.white)
                    .padding(.horizontal, dp(18)).padding(.vertical, dp(8))
                    .navigationGlass(in: Capsule())
                    .transition(.opacity)
            }
            if visible {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).frame(height: dp(150))
                    Spacer()
                    ZStack(alignment: .bottom) {
                        LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).frame(height: dp(200))
                        controls.padding(.horizontal, dp(32)).padding(.bottom, dp(24))
                    }
                }
                .ignoresSafeArea()
                .transition(.opacity)
            }
        }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: dp(10)) {
            Text(session.title).font(NuvioType.headlineMedium).foregroundStyle(.white).lineLimit(1)
            if let subtitle = session.subtitle {
                Text(subtitle).font(NuvioType.titleMedium).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
            }
            if state.isLive {
                HStack(spacing: dp(6)) {
                    Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(24), height: dp(24))
                    Text("LIVE").font(NuvioType.inter(16, .bold))
                }
                .foregroundStyle(colors.primary)
            } else {
                SeekBar(positionMs: scrubMs ?? state.positionMs, bufferedMs: state.bufferedMs, durationMs: state.durationMs,
                        scrubbing: scrubMs != nil, onSeek: { session.seekBy(offsetMs: $0); onActivity() })
            }
            HStack(spacing: dp(4)) {
                PlayerButton(icon: state.isPlaying ? "ic_player_pause" : "ic_player_play") { session.togglePlayPause(); onActivity() }
                Spacer()
                if session.seriesMeta != nil { PlayerButton(icon: "ic_player_episodes") { onShowPanel(.episodes) } }
                if !state.isLive { PlayerButton(icon: "ic_player_source") { onShowPanel(.sources) } }
                PlayerButton(icon: "ic_player_subtitles") { onShowPanel(.subtitles) }
                PlayerButton(icon: "ic_player_audio_filled") { onShowPanel(.audio) }
                PlayerButton(icon: "ic_player_aspect_ratio") { onShowPanel(.aspect) }
            }
            .focusSection()
        }
    }
}

private struct SeekBar: View {
    let positionMs: Int64
    let bufferedMs: Int64
    let durationMs: Int64
    let scrubbing: Bool
    let onSeek: (Int64) -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        let total = Double(max(1, durationMs))
        let played = min(1, Double(positionMs) / total)
        let buffered = min(1, Double(bufferedMs) / total)
        let height = focused || scrubbing ? dp(12) : dp(8)
        VStack(alignment: .leading, spacing: dp(6)) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: dp(3)).fill(Color.white.opacity(focused || scrubbing ? 0.45 : 0.3))
                    RoundedRectangle(cornerRadius: dp(3)).fill(colors.secondary.opacity(0.35)).frame(width: geo.size.width * buffered)
                    RoundedRectangle(cornerRadius: dp(3)).fill(colors.secondary).frame(width: geo.size.width * played)
                    if scrubbing {
                        Text(Self.clock(positionMs)).font(NuvioType.labelLargeSemi).foregroundStyle(.white)
                            .padding(.horizontal, dp(8)).padding(.vertical, dp(4))
                            .navigationGlass(in: Capsule())
                            .offset(x: max(0, geo.size.width * played - dp(30)), y: -dp(28))
                    }
                }
                .frame(height: height)
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: dp(16))
            HStack {
                Text(Self.clock(positionMs))
                Spacer()
                Text("-" + Self.clock(max(0, durationMs - positionMs)))
            }
            .font(NuvioType.bodyMedium).foregroundStyle(.white.opacity(0.68)).monospacedDigit()
        }
        .focusable()
        .focused($focused)
        .onMoveCommand { direction in
            if direction == .left { onSeek(-10_000) } else if direction == .right { onSeek(10_000) }
        }
        .animation(NuvioTokens.Motion.fast, value: focused)
    }

    static func clock(_ ms: Int64) -> String {
        let total = Int(max(0, ms) / 1000)
        let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

/// 48dp circle, 28dp custom icon (res/raw/ic_player_*); focused = white fill + black icon.
private struct PlayerButton: View {
    let icon: String
    let action: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().scaledToFit()
                .frame(width: dp(28), height: dp(28))
                .foregroundStyle(focused ? Color.black : Color.white)
                .frame(width: dp(48), height: dp(48))
                .background(Circle().fill(focused ? Color.white : Color.clear))
                .navigationGlass(in: Circle())
                .scaleEffect(focused ? 1.08 : 1)
                .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}

/// NuvioTV's in-player side panel (520dp, BackgroundElevated, 16dp left corners), as glass on tvOS 26:
/// subtitle / audio track lists and the aspect options.
private struct TrackPanel: View {
    let session: TvPlayerSession
    let kind: PlayerPanel
    let onClose: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var tracks: [TvTrack] = []

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: dp(12)) {
                HStack(spacing: dp(8)) {
                    PanelTab(title: "Subtitles", selected: kind == .subtitles) { switchTo(.subtitles) }
                    PanelTab(title: "Audio", selected: kind == .audio) { switchTo(.audio) }
                    PanelTab(title: "Aspect", selected: kind == .aspect) { switchTo(.aspect) }
                }
                .focusSection()
                ScrollView {
                    VStack(spacing: dp(6)) {
                        switch current {
                        case .subtitles:
                            PanelRow(title: "Off", checked: !tracks.contains { $0.selected }) { session.selectSubtitle(trackId: -1); reload() }
                            ForEach(tracks, id: \.id) { t in
                                PanelRow(title: t.label.isEmpty ? t.language : t.label, detail: t.language, checked: t.selected) { session.selectSubtitle(trackId: t.id); reload() }
                            }
                        case .audio:
                            ForEach(tracks, id: \.id) { t in
                                PanelRow(title: t.label.isEmpty ? t.language : t.label, detail: t.language, checked: t.selected) { session.selectAudio(trackId: t.id); reload() }
                            }
                            if tracks.isEmpty { Text("No other audio tracks").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary) }
                        case .aspect:
                            ForEach([(0, "Fit"), (1, "Fill"), (2, "Zoom")], id: \.0) { mode, title in
                                PanelRow(title: title, checked: aspect == mode) { aspect = mode; session.setResizeMode(mode: Int32(mode)) }
                            }
                        case .episodes, .sources:
                            EmptyView()
                        }
                    }
                }
                .focusSection()
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .frame(maxHeight: .infinity)
            .navigationGlass(in: UnevenRoundedRectangle(topLeadingRadius: dp(16), bottomLeadingRadius: dp(16)))
        }
        .ignoresSafeArea()
        .onAppear { current = kind; reload() }
        .onExitCommand(perform: onClose)
    }

    @State private var current: PlayerPanel = .subtitles
    @AppStorage("tvos.player.aspect") private var aspect = 0

    private func switchTo(_ panel: PlayerPanel) { current = panel; reload() }
    private func reload() {
        tracks = current == .audio ? session.audioTracks() : (current == .subtitles ? session.subtitleTracks() : [])
    }
}

private struct PanelTab: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    var body: some View { HubChip(title: title, selected: selected, action: action) }
}

private struct PanelRow: View {
    let title: String
    var detail: String? = nil
    let checked: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                Image("md_check_circle").renderingMode(.template).resizable().frame(width: dp(18), height: dp(18))
                    .foregroundStyle(checked ? colors.secondary : .clear)
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text(title).font(NuvioType.bodyLarge).foregroundStyle(focused ? Color.black : colors.textPrimary).lineLimit(1)
                    if let detail, !detail.isEmpty, detail != title {
                        Text(detail).font(NuvioType.bodySmall).foregroundStyle(focused ? Color.black.opacity(0.7) : colors.textSecondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, dp(16)).padding(.vertical, dp(10))
            .background(RoundedRectangle(cornerRadius: dp(12)).fill(focused ? Color.white : Color.clear))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}


/// NuvioTV NextEpisodeOverlay: a card bottom-right near the end of an episode. Selecting it opens the
/// next episode's sources.
private struct NextEpisodeCard: View {
    let video: MetaVideo
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: dp(12)) {
                CachedPosterArtwork(urlString: video.thumbnail, width: dp(128), height: dp(72), maximumWidth: dp(256)) { colors.backgroundCard }
                    .frame(width: dp(128), height: dp(72)).clipShape(RoundedRectangle(cornerRadius: dp(8)))
                VStack(alignment: .leading, spacing: dp(2)) {
                    Text("Next Episode").font(NuvioType.labelMedium).foregroundStyle(focused ? Color.black.opacity(0.7) : colors.textSecondary)
                    Text("S\(video.season?.int32Value ?? 0) E\(video.episode?.int32Value ?? 0) · \(video.title)")
                        .font(NuvioType.titleMedium).foregroundStyle(focused ? Color.black : colors.textPrimary).lineLimit(1)
                }
                Image("md_play_arrow").renderingMode(.template).resizable().frame(width: dp(22), height: dp(22))
                    .foregroundStyle(focused ? Color.black : Color.white)
            }
            .padding(dp(12))
            .frame(maxWidth: dp(460))
            .background(RoundedRectangle(cornerRadius: dp(16)).fill(focused ? Color.white : Color.clear))
            .navigationGlass(in: RoundedRectangle(cornerRadius: dp(16)))
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
    }
}

/// Episodes (this season) and Sources (this title's other sources) side panels, as NuvioTV's player panels.
private struct ContentPanel: View {
    let session: TvPlayerSession
    let kind: PlayerPanel
    let onClose: () -> Void
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var streams: StreamsUiState?
    @State private var switching = false

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: dp(12)) {
                Text(kind == .episodes ? "Episodes" : "Sources").font(NuvioType.titleLarge).foregroundStyle(colors.textPrimary)
                ScrollView {
                    VStack(spacing: dp(6)) {
                        if kind == .episodes, let meta = session.seriesMeta {
                            let season = session.currentSeason?.int32Value
                            ForEach(meta.videos.filter { $0.season?.int32Value == season }, id: \.id) { video in
                                PanelRow(title: "E\(video.episode?.int32Value ?? 0) · \(video.title)", detail: video.overview,
                                         checked: video.episode?.int32Value == session.currentEpisode?.int32Value) {
                                    playback.openSources(meta: meta, video: video)
                                }
                            }
                        } else {
                            let groups = (streams?.groups ?? []).filter { !$0.streams.isEmpty }
                            if groups.isEmpty {
                                Text("No other sources loaded for this title.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                            }
                            ForEach(groups, id: \.addonId) { group in
                                ForEach(Array(group.streams.enumerated()), id: \.offset) { _, stream in
                                    PanelRow(title: stream.streamLabel, detail: group.addonName, checked: false) { switchTo(stream) }
                                }
                            }
                        }
                    }
                }
                .focusSection()
            }
            .padding(dp(24))
            .frame(width: dp(520))
            .frame(maxHeight: .infinity)
            .navigationGlass(in: UnevenRoundedRectangle(topLeadingRadius: dp(16), bottomLeadingRadius: dp(16)))
        }
        .ignoresSafeArea()
        .onExitCommand(perform: onClose)
        .task { for await next in TvTitle.shared.streams { streams = next } }
    }

    /// Switch source in place: the new source opens at the current position.
    private func switchTo(_ stream: StreamItem) {
        guard !switching, let meta = session.seriesMeta ?? TvTitle.shared.details.value.meta else { return }
        switching = true
        let video = meta.videos.first { $0.id == session.currentVideoId }
        let resume = session.state.value.positionMs
        Task {
            defer { switching = false }
            if let result = try? await TvTitle.shared.open(stream: stream, meta: meta, video: video, resumeMs: resume) {
                switch onEnum(of: result) {
                case .play(let play): playback.play(play.session)
                case .message(let message): playback.notify(message.text)
                }
            }
        }
    }
}
