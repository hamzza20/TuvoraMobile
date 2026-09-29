import SwiftUI
import TuvoraCore

/// Keeps every title-actions menu current: one subscription to the library and watched state, and a
/// version the menus read so their labels ("Add to library" / "Remove from library") never go stale.
@MainActor
final class TitleActionsModel: ObservableObject {
    static let shared = TitleActionsModel()
    @Published private(set) var version = 0
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        Task { @MainActor in for await _ in TvTitle.shared.libraryChanges { self.version += 1 } }
        Task { @MainActor in for await _ in TvTitle.shared.watchedChanges { self.version += 1 } }
    }
}

extension View {
    /// NuvioTV's long-press poster menu (posteroptions/PosterOptionsDialog.kt, "Title actions"): Go to
    /// details, Add to / Remove from library, Mark as watched / unwatched. On Apple TV a long press on
    /// the Select button opens the system context menu. Live channels have no menu (OK plays them).
    func titleActions(_ preview: MetaPreview, libraryItem: TuvoraCore.LibraryItem? = nil, onDetails: @escaping () -> Void) -> some View {
        modifier(TitleActionsModifier(preview: preview, libraryItem: libraryItem, onDetails: onDetails))
    }
}

private struct TitleActionsModifier: ViewModifier {
    let preview: MetaPreview
    let libraryItem: TuvoraCore.LibraryItem?
    let onDetails: () -> Void
    @ObservedObject private var model = TitleActionsModel.shared
    @State private var confirmation: TvRemovalConfirmation?

    func body(content: Content) -> some View {
        if IptvContentClassifierAccess.shared.classifier.isLiveId(id: preview.id) {
            content
        } else {
            let _ = model.version
            let saved = TvTitleActions.shared.isSaved(preview: preview)
            content
                .contextMenu {
                    Button("Go to details", action: onDetails)
                    Button(saved ? "Remove from library" : "Add to library") { toggleSaved(confirmed: false) }
                    if TvTitleActions.shared.canMarkWatched(preview: preview) {
                        Button(TvTitleActions.shared.isWatched(preview: preview) ? "Mark as unwatched" : "Mark as watched") {
                            Task { try? await TvTitleActions.shared.toggleWatched(preview: preview) }
                        }
                    }
                }
                .onAppear { model.start() }
                .alert(confirmation?.title ?? "", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } })) {
                    Button("Remove anyway", role: .destructive) { confirmation = nil; toggleSaved(confirmed: true) }
                    Button("Cancel", role: .cancel) { confirmation = nil }
                } message: {
                    Text(confirmation?.message ?? "")
                }
        }
    }

    private func toggleSaved(confirmed: Bool) {
        Task { @MainActor in
            let result: TvSaveResult?
            if let libraryItem {
                result = try? await TvTitleActions.shared.toggleSavedItem(item: libraryItem, confirmed: confirmed)
            } else {
                result = try? await TvTitleActions.shared.toggleSaved(preview: preview, confirmed: confirmed)
            }
            if let pending = result?.confirmation { confirmation = pending }
        }
    }
}
