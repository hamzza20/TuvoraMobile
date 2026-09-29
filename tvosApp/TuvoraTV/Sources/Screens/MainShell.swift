import SwiftUI
import TuvoraCore

/// The signed-in app: the tvOS top tab bar over the main sections, and the one full-screen player.
struct MainShell: View {
    @StateObject private var playback = PlaybackCoordinator()
    /// tvOS builds every tab at once, but the shared IPTV hub holds ONE section (Live, Movies or
    /// Series) at a time: only the visible tab may drive it, or a hidden tab swaps the data under it.
    @State private var tab = Tab.live

    enum Tab: Hashable { case live, movies, series, settings }

    var body: some View {
        TabView(selection: $tab) {
            LiveTvScreen(isActive: tab == .live).tabItem { Text("Live TV") }.tag(Tab.live)
            IptvLibraryScreen(section: .movies, isActive: tab == .movies).tabItem { Text("Movies") }.tag(Tab.movies)
            IptvLibraryScreen(section: .series, isActive: tab == .series).tabItem { Text("Series") }.tag(Tab.series)
            SettingsScreen().tabItem { Text("Settings") }.tag(Tab.settings)
        }
        .environmentObject(playback)
        .background(Theme.background.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let message = playback.message {
                Text(message)
                    .font(.callout)
                    .padding(.horizontal, 32).padding(.vertical, 16)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 60)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut, value: playback.message)
        .fullScreenCover(item: $playback.session) { box in
            TvPlayerScreen(session: box.session) { playback.stop() }
        }
    }
}
