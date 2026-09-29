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

/// Full-screen playback over either engine, with one Tuvora overlay.
/// Siri Remote: click / play-pause toggles, left/right skip 10 s (VOD), Menu hides the overlay or leaves.
struct TvPlayerScreen: View {
    let session: TvPlayerSession
    let onClose: () -> Void

    @State private var state: TvPlayerState?
    @State private var overlayVisible = true
    @State private var hideTask: Task<Void, Never>?
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            EngineHost(session: session, generation: Int(state?.engineGeneration ?? 0))
                .ignoresSafeArea()
            if let state {
                PlayerOverlay(title: session.title, subtitle: session.subtitle, state: state, visible: overlayVisible || state.errorMessage != nil)
            }
        }
        .focusable()
        .focused($focused)
        .onAppear {
            focused = true
            session.attach()
            bumpOverlay()
        }
        .onDisappear { session.detach() }
        .onPlayPauseCommand { session.togglePlayPause(); bumpOverlay() }
        .onTapGesture {
            if state?.errorMessage != nil { session.retry() } else { session.togglePlayPause() }
            bumpOverlay()
        }
        .onMoveCommand { direction in
            switch direction {
            case .left: session.seekBy(offsetMs: -10_000)
            case .right: session.seekBy(offsetMs: 10_000)
            default: break
            }
            bumpOverlay()
        }
        .onExitCommand {
            if overlayVisible && state?.isPlaying == true {
                overlayVisible = false
            } else {
                session.close()
                onClose()
            }
        }
        .task {
            for await next in session.state {
                state = next
                NSLog("SMOKE player lane=%@ gen=%d loading=%d playing=%d pos=%lld dur=%lld err=%@",
                      "\(next.lane)", next.engineGeneration, next.isLoading, next.isPlaying,
                      next.positionMs, next.durationMs, next.errorMessage ?? "-")
            }
        }
    }

    private func bumpOverlay() {
        overlayVisible = true
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled && state?.isPlaying == true { overlayVisible = false }
        }
    }
}

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

private struct PlayerOverlay: View {
    let title: String
    let subtitle: String?
    let state: TvPlayerState
    let visible: Bool

    var body: some View {
        VStack {
            Spacer()
            if let error = state.errorMessage {
                VStack(spacing: 12) {
                    Text("Playback failed").font(.title2).bold()
                    Text(error).font(.body).foregroundStyle(.secondary)
                    Text("Press the touch surface to try again").font(.callout)
                }
                .padding(40)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
                Spacer()
            } else if state.isLoading {
                ProgressView().scaleEffect(1.6)
                Spacer()
            }
            if visible {
                VStack(alignment: .leading, spacing: 16) {
                    Text(title).font(.title2).bold().lineLimit(1)
                    if let subtitle { Text(subtitle).font(.callout).foregroundStyle(.secondary).lineLimit(1) }
                    if state.isLive {
                        Label("LIVE", systemImage: "dot.radiowaves.left.and.right").font(.callout.bold()).foregroundStyle(.red)
                    } else {
                        ProgressView(value: Double(max(0, state.positionMs)), total: Double(max(1, state.durationMs)))
                        HStack {
                            Text(Self.clock(state.positionMs))
                            Spacer()
                            Text(Self.clock(state.durationMs))
                        }
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                    }
                }
                .padding(48)
                .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: visible)
    }

    static func clock(_ ms: Int64) -> String {
        let total = Int(max(0, ms) / 1000)
        let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
