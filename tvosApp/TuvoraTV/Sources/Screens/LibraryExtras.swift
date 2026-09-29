import SwiftUI
import TuvoraCore

// NuvioTV Library extras: the Cloud (debrid) view and Manage Lists (library/LibraryScreen.kt,
// library/LibraryListDialogs.kt).

// MARK: - Cloud

/// LibraryScreen.kt Cloud view: provider / type pickers, a search field that filters the cloud list only,
/// then one full-width card per item. OK plays the file (a picker when there are several).
struct CloudLibraryView: View {
    @EnvironmentObject private var playback: PlaybackCoordinator
    @Environment(\.nuvio) private var colors
    @State private var state: CloudLibraryUiState = TvCloudLibrary.shared.state.value
    @State private var provider: String?
    @State private var type: String?
    @State private var query = ""
    @State private var picking: CloudItemBox?
    @State private var resolving: String?

    private var items: [CloudLibraryItem] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return state.items.filter { item in
            (provider == nil || item.providerId == provider) && (type == nil || String(describing: item.type) == type)
                && (q.isEmpty || item.name.lowercased().contains(q) || item.files.contains { $0.name.lowercased().contains(q) })
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            HStack(spacing: dp(12)) {
                let providers = state.providers.map { NuvioPickerOption(value: $0.providerId, label: $0.providerName) }
                NuvioDropdownPicker(title: "Select provider", value: providers.first { $0.value == provider }?.label ?? "All",
                                    selectedValue: provider ?? "__all__",
                                    options: [NuvioPickerOption(value: "__all__", label: "All")] + providers) {
                    provider = $0.value == "__all__" ? nil : $0.value
                }
                let types = Array(Set(state.items.map { String(describing: $0.type) })).sorted()
                NuvioDropdownPicker(title: "Select type", value: type.map(Self.typeLabel) ?? "All", selectedValue: type ?? "__all__",
                                    options: [NuvioPickerOption(value: "__all__", label: "All")] + types.map { NuvioPickerOption(value: $0, label: Self.typeLabel($0)) }) {
                    type = $0.value == "__all__" ? nil : $0.value
                }
            }
            .focusSection()
            TextField("Search cloud library", text: $query).font(NuvioType.bodyMedium)
            content
        }
        .task {
            TvCloudLibrary.shared.ensureLoaded()
            for await next in TvCloudLibrary.shared.state { state = next }
        }
        .sheet(item: $picking) { box in
            CloudFilePicker(item: box.item) { file in picking = nil; play(box.item, file) }.environment(\.nuvio, colors)
        }
    }

    @ViewBuilder
    private var content: some View {
        if state.isRefreshing && state.items.isEmpty {
            VStack(spacing: dp(14)) {
                ProgressView()
                Text("Syncing library…").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            }
            .frame(maxWidth: .infinity).frame(height: dp(260))
        } else if !state.isEnabled {
            NuvioEmptyState(icon: "md_bookmark_border", title: "Cloud library is off",
                            subtitle: "Turn on Cloud library in Connected Services settings to browse files from connected accounts.", height: dp(260))
        } else if !state.hasConnectedProvider {
            NuvioEmptyState(icon: "md_bookmark_border", title: "No cloud account connected",
                            subtitle: "Connect an account in Connected Services settings to browse playable files from your cloud library.", height: dp(260))
        } else if items.isEmpty {
            NuvioEmptyState(icon: "md_bookmark_border", title: "Nothing here yet",
                            subtitle: "No playable cloud files match the current filters.", height: dp(260))
        } else {
            LazyVStack(spacing: dp(12)) {
                ForEach(items, id: \.stableKey) { item in
                    CloudLibraryCard(item: item, resolving: resolving == item.stableKey) {
                        switch item.playableFiles.count {
                        case 0: playback.notify("This item does not expose a playable video file.")
                        case 1: play(item, item.playableFiles[0])
                        default: picking = CloudItemBox(item: item)
                        }
                    }
                }
            }
            .focusSection()
        }
    }

    private func play(_ item: CloudLibraryItem, _ file: CloudLibraryFile) {
        resolving = item.stableKey
        Task {
            let session = try? await TvCloudLibrary.shared.play(item: item, file: file)
            resolving = nil
            if let session { playback.play(session) } else { playback.notify("Couldn't play this cloud file.") }
        }
    }

    static func typeLabel(_ raw: String) -> String {
        switch raw.lowercased() {
        case "torrent": return "Torrents"
        case "usenet": return "Usenet"
        case "webdownload": return "Web"
        default: return "Files"
        }
    }
}

private struct CloudItemBox: Identifiable {
    let item: CloudLibraryItem
    var id: String { item.stableKey }
}

/// CloudLibraryCard: BackgroundCard, radius 10, 1dp Border (focused FocusBackground, 2dp ring, 1.02),
/// padding 18×14; name titleSmall SemiBold, file line bodyMedium, "provider • type • status • size"
/// bodySmall TextTertiary with the playable-file count (labelMedium Primary) on the right.
private struct CloudLibraryCard: View {
    let item: CloudLibraryItem
    let resolving: Bool
    let action: () -> Void
    @Environment(\.nuvio) private var colors
    @FocusState private var focused: Bool

    var body: some View {
        Button(action: { if !resolving { action() } }) {
            VStack(alignment: .leading, spacing: dp(9)) {
                Text(item.name).font(NuvioType.inter(14, .semibold)).foregroundStyle(colors.textPrimary).lineLimit(2)
                if let line = fileLine { Text(line).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary).lineLimit(1) }
                HStack {
                    Text(metadata).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary).lineLimit(1)
                    Spacer()
                    Text(resolving ? "Opening…" : TvCloudLibrary.shared.fileCountLabel(item: item)).font(NuvioType.labelMedium)
                        .foregroundStyle(item.playableFiles.isEmpty ? colors.textTertiary : colors.primary)
                }
            }
            .padding(.horizontal, dp(18)).padding(.vertical, dp(14))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: dp(10), style: .continuous).fill(focused ? colors.focusBackground : colors.backgroundCard))
            .overlay(RoundedRectangle(cornerRadius: dp(10), style: .continuous)
                .stroke(focused ? colors.focusRing : colors.border, lineWidth: focused ? NuvioTokens.Stroke.focus : NuvioTokens.Stroke.hairline))
            .scaleEffect(focused ? NuvioTokens.Motion.focusScale : 1)
            .animation(NuvioTokens.Motion.fast, value: focused)
        }
        .buttonStyle(PlainNoChromeButtonStyle())
        .focused($focused)
        .reportsFocus(focused)
    }

    private var fileLine: String? {
        let files = item.playableFiles
        switch files.count {
        case 0: return "No playable files"
        case 1: return files[0].name == item.name ? nil : files[0].name
        default: return "\(files.count) playable files"
        }
    }

    private var metadata: String {
        var parts = [item.providerName, CloudLibraryView.typeLabel(String(describing: item.type)), item.status.flatMap { $0.isEmpty ? nil : $0 } ?? "Ready to play"]
        if let size = item.sizeBytes?.int64Value, size > 0 {
            let gb = Double(size) / 1_000_000_000
            parts.append(gb >= 1 ? String(format: "%.1f GB", gb) : String(format: "%.0f MB", Double(size) / 1_000_000))
        }
        return parts.filter { !$0.isEmpty }.joined(separator: " • ")
    }
}

/// CloudFilePickerDialog: "Choose a file to play" over the item's playable files.
private struct CloudFilePicker: View {
    let item: CloudLibraryItem
    let onPlay: (CloudLibraryFile) -> Void

    var body: some View {
        NuvioDialog(title: "Choose a file to play", subtitle: item.name) {
            ForEach(item.playableFiles, id: \.stableKey) { file in
                SettingsActionRow(title: file.name, value: "Play file") { onPlay(file) }
            }
        }
    }
}

// MARK: - Manage Lists

/// LibraryListDialogs.kt ManageListsDialog / ListEditorDialog / delete confirmation, over TvLibraryLists
/// (the phone's LibraryListManagementController): pick a personal list, then Create / Edit / Move Up /
/// Move Down / Delete / Close; the editor has Name, Description (when the provider has it), Privacy.
struct ManageListsSheet: View {
    let onClose: () -> Void
    @Environment(\.nuvio) private var colors
    @State private var dialog: LibraryListDialogState?
    @State private var selectedKey: String?
    @State private var info: TvListsInfo? = TvLibraryLists.shared.info()
    @State private var message: String?

    var body: some View {
        Group {
            if let dialog, dialog.mode == .edit {
                editor(dialog)
            } else if let dialog, dialog.mode == .delete {
                NuvioDialog(title: "Delete this list?", subtitle: "This removes the list and all list items from \(info?.providerName ?? "the provider").") {
                    errorLine(dialog)
                    SettingsActionRow(title: dialog.isPending ? "Deleting…" : "Delete", showChevron: false) { submit() }
                    SettingsActionRow(title: "Cancel", showChevron: false) { TvLibraryLists.shared.open() }
                }
            } else {
                manage
            }
        }
        .task {
            TvLibraryLists.shared.open()
            for await next in TvLibraryLists.shared.dialog {
                dialog = next
                info = TvLibraryLists.shared.info()
                if selectedKey == nil { selectedKey = info?.lists.first?.key }
            }
        }
        .onDisappear { TvLibraryLists.shared.dismiss() }
    }

    private var manage: some View {
        NuvioDialog(title: "Manage \(info?.providerName ?? "") Lists") {
            if let lists = info?.lists, !lists.isEmpty {
                ForEach(lists, id: \.key) { tab in
                    SettingsActionRow(title: tab.title, value: tab.key == selectedKey ? "Selected" : nil, showChevron: false) { selectedKey = tab.key }
                }
            } else {
                Text("No personal lists yet.").font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
            }
            if let message { Text(message).font(NuvioType.bodySmall).foregroundStyle(colors.error) }
            HStack(spacing: dp(10)) {
                NuvioTextButton(title: "Create") { TvLibraryLists.shared.create() }
                NuvioTextButton(title: "Edit", enabled: selectedKey != nil) { if let k = selectedKey { TvLibraryLists.shared.edit(key: k) } }
                if info?.supportsReordering == true {
                    NuvioTextButton(title: "Move Up", enabled: selectedKey != nil) { move(up: true) }
                    NuvioTextButton(title: "Move Down", enabled: selectedKey != nil) { move(up: false) }
                }
            }
            .focusSection()
            HStack(spacing: dp(10)) {
                NuvioTextButton(title: "Delete", enabled: selectedKey != nil) { if let k = selectedKey { TvLibraryLists.shared.requestDelete(key: k) } }
                NuvioTextButton(title: "Close", action: onClose)
            }
            .focusSection()
        }
    }

    private func editor(_ state: LibraryListDialogState) -> some View {
        NuvioDialog(title: state.key == nil ? "Create List" : "Edit List") {
            TextField("Name", text: Binding(get: { state.name }, set: { TvLibraryLists.shared.setName(value: $0) }))
            if info?.supportsDescription == true {
                TextField("Description", text: Binding(get: { state.description_ }, set: { TvLibraryLists.shared.setDescription(value: $0) }))
            }
            if let options = info?.privacyOptions, options.count > 1 {
                Text("Privacy").font(NuvioType.labelLarge).foregroundStyle(colors.textSecondary)
                HStack(spacing: dp(8)) {
                    ForEach(options, id: \.self) { option in
                        SettingsChoiceChip(label: Self.privacyLabel(option), selected: state.privacy == option) { TvLibraryLists.shared.setPrivacy(value: option) }
                    }
                }
                .focusSection()
            }
            errorLine(state)
            SettingsActionRow(title: state.isPending ? "Saving…" : "Save", showChevron: false) {
                guard !state.name.trimmingCharacters(in: .whitespaces).isEmpty else { message = "List name is required"; return }
                submit()
            }
            SettingsActionRow(title: "Cancel", showChevron: false) { TvLibraryLists.shared.open() }
        }
    }

    @ViewBuilder
    private func errorLine(_ state: LibraryListDialogState) -> some View {
        if let text = TvLibraryLists.shared.errorText(state: state) {
            Text(text).font(NuvioType.bodySmall).foregroundStyle(colors.error)
        }
    }

    private func submit() { Task { try? await TvLibraryLists.shared.submit() } }

    private func move(up: Bool) {
        guard let key = selectedKey else { return }
        Task {
            let ok = (try? await TvLibraryLists.shared.move(key: key, up: up))?.boolValue ?? false
            message = ok ? nil : "Failed to reorder lists"
            info = TvLibraryLists.shared.info()
        }
    }

    static func privacyLabel(_ privacy: LibraryListPrivacy) -> String {
        switch privacy {
        case .link: return "Link"
        case .friends: return "Friends"
        case .public: return "Public"
        default: return "Private"
        }
    }
}
