import SwiftUI
import TuvoraCore

// Settings -> Integrations -> Media servers (Jellyfin / Emby as sources), Apple TV, on the SAME shared stack the phones use.
//
//   LIST      "Add a server" + the saved servers, each with a status badge (Signed in / Sign in again / ...).
//   ADD       one dialog, steps: product + address -> (certificate: trust on first use, or "certificate changed") ->
//             Quick Connect code OR username + password.
//   DETAILS   a full-screen page: use this server, add-on details, watch progress, libraries, sign in again, remove.
//
// Every decision lives in the shared Kotlin (TvMediaServers.kt over features/servers); this file only draws it. Typing on a
// TV remote is painful, so the Quick Connect code is offered first, as Swiftfin's tvOS sign-in does. Sign-in tokens and the
// trusted-certificate pins never leave this device. Text comes from the shared string tables (servers_* keys) with an
// English fallback.

/// Looks a `servers_*` string up in the shared Tuvora.strings table (Android placeholders: %1$s, %s).
func MS(_ key: String, _ english: String, _ args: String...) -> String {
    var text = Bundle.main.localizedString(forKey: key, value: english, table: "Tuvora")
    for (i, arg) in args.enumerated() {
        text = text.replacingOccurrences(of: "%\(i + 1)$s", with: arg)
    }
    if let first = args.first { text = text.replacingOccurrences(of: "%s", with: first) }
    return text
}

private extension TvServerStatus {
    var label: String {
        switch self {
        case .signedIn: return "Signed in"
        case .signInAgain: return MS("servers_status_auth", "Sign in again")
        case .needsSignIn: return "Sign in on this device"
        case .offline: return MS("servers_status_unreachable", "Server unavailable")
        case .disabled: return MS("servers_status_disabled", "Disabled")
        default: return ""
        }
    }
}

// MARK: - List (a pane of the Integrations hub)

struct MediaServersSettingsDetail: View {
    @EnvironmentObject private var dialogs: SettingsDialogs
    @Environment(\.scenePhase) private var scenePhase
    @State private var rows: [TvServerRow] = []

    var body: some View {
        VStack(alignment: .leading, spacing: dp(12)) {
            SettingsDetailHeader(title: MS("compose_settings_page_media_servers", "Media servers"),
                                 subtitle: MS("settings_content_discovery_media_servers_description", "Connect Jellyfin or Emby to browse and play your own library."))
            SettingsGroupCard {
                SettingsActionRow(title: "Add a server",
                                  subtitle: "Jellyfin or Emby, on your network or online",
                                  leadingIcon: "md_add") {
                    dialogs.push(.mediaServerAdd(nil))
                }
                .accessibilityIdentifier("mediaServers.add")
                ForEach(rows, id: \.key) { row in
                    SettingsActionRow(title: row.name, subtitle: row.subtitle,
                                      value: row.checking && row.status == .signedIn ? "Checking..." : row.status.label,
                                      valueColor: row.status == .signInAgain || row.status == .needsSignIn ? nil : nil) {
                        // A server that needs this device's sign-in goes straight to sign-in; the rest to details.
                        dialogs.push(.mediaServerDetails(row.key))
                    }
                    .accessibilityIdentifier("mediaServers.row.\(row.name)")
                }
            }
            if rows.isEmpty {
                SettingsHelperText(text: MS("servers_empty", "No servers connected yet.") + " Your sign-in stays on this device.")
            }
        }
        .task { for await next in TvMediaServers.shared.rows { rows = next } }
        // One reachability check per visit (never a timer): when the pane appears and when the app returns.
        .task(id: scenePhase) { if scenePhase == .active { TvMediaServers.shared.checkOnce() } }
        // Back from a dialog (add / details) re-checks once, as the phone does when the page resumes.
        .task(id: dialogs.stack.count) { if dialogs.stack.isEmpty { TvMediaServers.shared.checkOnce() } }
    }
}

// MARK: - Add a server / sign in

private enum AddFocus: Hashable {
    case address, connect, type(String), quickConnect, password, changeAddress, retry
    case username, passwordField, signIn, back, trust, decline, usePassword
}

struct MediaServerAddDialog: View {
    let existingKey: String?
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model: MediaServerAddModel
    @State private var username = ""
    @State private var password = ""
    @State private var now = Date()
    @FocusState private var focus: AddFocus?

    init(existingKey: String?, dialogs: SettingsDialogs) {
        self.existingKey = existingKey
        self.dialogs = dialogs
        _model = StateObject(wrappedValue: MediaServerAddModel(existingKey: existingKey))
    }

    var body: some View {
        let s = model.state
        NuvioDialog(title: title(s), subtitle: subtitle(s), width: dp(760)) {
            switch s.stage {
            case .address: addressStep(s)
            case .chooseSignIn: chooseStep(s)
            case .quickConnect: quickConnectStep(s)
            case .password: passwordStep(s)
            case .done: ProgressView().task { dialogs.pop() }
            default: EmptyView()
            }
        }
        .onChange(of: s.stage) { _, stage in focusFirst(stage) }
        .onChange(of: s.certAuthority) { _, cert in if cert != nil { setFocus(.trust) } }
        .onAppear { focusFirst(s.stage) }
        .onDisappear { model.close() }
        .onExitCommand { back(s) }
        // The Quick Connect poll lives only while the code screen is visible and the app is active; task
        // cancellation cancels the Kotlin coroutine (nothing polls in the background). A new request (Try again)
        // bumps the attempt and so restarts the poll.
        .task(id: PollKey(stage: s.stage, active: scenePhase == .active, attempt: s.quickConnectAttempt, hasCode: s.quickConnectCode != nil)) {
            guard s.stage == .quickConnect, scenePhase == .active, s.quickConnectCode != nil else { return }
            try? await model.session.runQuickConnect()
        }
        .task(id: s.quickConnectStartedAtMs) {
            while !Task.isCancelled && s.stage == .quickConnect {
                now = Date()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private struct PollKey: Hashable { let stage: TvAddStage; let active: Bool; let attempt: Int32; let hasCode: Bool }

    private func title(_ s: TvAddState) -> String {
        switch s.stage {
        case .quickConnect: return "Enter this code to sign in"
        case .password: return MS("servers_username", "Username")
        default:
            let name = s.selectedType == "emby" ? "Emby" : "Jellyfin"
            return existingKey != nil ? MS("servers_sign_in_title", "Sign in to %1$s", name) : "Add a server"
        }
    }

    private func subtitle(_ s: TvAddState) -> String? {
        switch s.stage {
        case .address: return "Type your server\u{2019}s address. Pick Jellyfin or Emby first."
        case .chooseSignIn:
            guard let name = s.foundName, let product = s.foundProduct else { return nil }
            return "Connected to \(name) (\(product))"
        case .quickConnect:
            return MS("servers_quick_connect_instructions", "On a device already signed in to %1$s, open Quick Connect and enter this code.", s.foundName ?? "")
        default: return nil
        }
    }

    // Step 1: the product, the address (tvOS native keyboard) and, when needed, the certificate decision.
    @ViewBuilder private func addressStep(_ s: TvAddState) -> some View {
        if existingKey == nil {
            HStack(spacing: dp(8)) {
                typeChip("Jellyfin", wire: "jellyfin", selected: s.selectedType == "jellyfin")
                typeChip("Emby", wire: "emby", selected: s.selectedType == "emby")
                Spacer()
            }
        }
        SettingsTextField(label: MS("servers_address", "Server address"),
                          hint: MS("servers_address_hint", "http://192.168.1.10:8096"),
                          text: Binding(get: { model.state.address }, set: { model.session.setAddress(text: $0) }),
                          onSubmit: { model.session.connect() }, id: "mediaServers.address")
            .focused($focus, equals: .address)
        if let e = s.error { errorLine(e) }
        if let cert = s.certAuthority, let fp = s.certFingerprint { certificate(cert, fp, changed: s.certChanged) }
        HStack(spacing: dp(8)) {
            Spacer()
            SettingsDialogButton(title: L("Cancel")) { dialogs.pop() }
            SettingsDialogButton(title: s.busy ? "Checking..." : MS("servers_connect", "Connect"), primary: true) {
                model.session.connect()
            }
            .focused($focus, equals: .connect)
            .accessibilityIdentifier("mediaServers.connect")
            .disabled(s.busy || s.address.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.top, dp(8))
    }

    private func typeChip(_ label: String, wire: String, selected: Bool) -> some View {
        SettingsChoiceChip(label: label, selected: selected) { model.session.selectType(wire: wire) }
            .focused($focus, equals: .type(wire))
            .accessibilityIdentifier("mediaServers.type.\(wire)")
    }

    /// The certificate the system did not trust (trust on first use), or one that differs from the one this device trusted.
    private func certificate(_ authority: String, _ fingerprint: String, changed: Bool) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text(ui: changed ? MS("servers_cert_changed_title", "Certificate changed") : MS("servers_cert_title", "Trust this certificate?"))
                .font(NuvioType.titleMedium).foregroundStyle(changed ? colors.error : colors.textPrimary)
            Text(ui: changed
                 ? MS("servers_cert_changed_message", "%1$s now presents a different certificate than the one you trusted. That can mean the server was reinstalled, or that someone is intercepting the connection. Only continue if you expect this.", authority)
                 : MS("servers_cert_message", "%1$s presented a certificate this device does not recognise. Only trust it if this is your own server.", authority))
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
            Text(verbatim: MS("servers_cert_fingerprint", "SHA-256 %1$s", fingerprint))
                .font(.system(size: dp(13), design: .monospaced)).foregroundStyle(colors.textPrimary)
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: L("Cancel")) { model.session.declineCertificate() }.focused($focus, equals: .decline)
                    .accessibilityIdentifier("mediaServers.decline")
                SettingsDialogButton(title: changed ? MS("servers_cert_trust_new", "Trust the new certificate") : MS("servers_cert_trust", "Trust certificate"),
                                     primary: true, destructive: changed) { model.session.trustCertificate() }
                    .focused($focus, equals: .trust)
                    .accessibilityIdentifier("mediaServers.trust")
            }
        }
        .padding(dp(14))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
    }

    // Step 2: how to sign in. Quick Connect first (typing a password with a remote is the slow way).
    @ViewBuilder private func chooseStep(_ s: TvAddState) -> some View {
        if s.quickConnectAvailable {
            SettingsActionRow(title: "Quick Connect",
                              subtitle: "Get a code and approve it from a device that is already signed in") {
                model.session.startQuickConnect()
            }
            .focused($focus, equals: .quickConnect)
            .accessibilityIdentifier("mediaServers.quickConnect")
        }
        SettingsActionRow(title: "Username and password",
                          subtitle: MS("servers_sign_in_subtitle_short", "Your password is used once to sign in and is never stored")) {
            model.session.usePassword()
        }
        .focused($focus, equals: .password)
        .accessibilityIdentifier("mediaServers.usePassword")
        if let e = s.error { errorLine(e) }
        SettingsActionRow(title: "Use a different address", showChevron: false) { model.session.backToAddress() }
            .focused($focus, equals: .changeAddress)
    }

    // Quick Connect: the code, big, with the countdown (Swiftfin shows the same: steps + a tracked code).
    @ViewBuilder private func quickConnectStep(_ s: TvAddState) -> some View {
        VStack(spacing: dp(10)) {
            if let code = s.quickConnectCode {
                Text(verbatim: code).font(.system(size: dp(54), weight: .bold, design: .monospaced)).tracking(dp(6))
                    .foregroundStyle(colors.textPrimary).accessibilityIdentifier("mediaServers.qcCode")
                Text(verbatim: "Expires in " + model.session.countdownLabel(startedAtMs: s.quickConnectStartedAtMs, nowMs: Int64(now.timeIntervalSince1970 * 1000)))
                    .font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                Text(ui: "Waiting for approval...").font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
            } else if s.error == nil {
                ProgressView()
            }
            if let e = s.error { errorLine(e) }
        }
        .frame(maxWidth: .infinity).padding(.vertical, dp(18))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
        if s.error != nil && s.error != .quickConnectOff {
            SettingsActionRow(title: MS("servers_quick_connect_retry", "Try again"), showChevron: false) { model.session.startQuickConnect() }
                .focused($focus, equals: .retry)
                .accessibilityIdentifier("mediaServers.qcRetry")
        }
        SettingsActionRow(title: MS("servers_quick_connect_back", "Sign in with a password"), showChevron: false) { model.session.usePassword() }
            .focused($focus, equals: .usePassword)
        SettingsActionRow(title: "Back", showChevron: false) { model.session.backToChoice() }
            .focused($focus, equals: .back)
    }

    // Password: the native keyboard.
    @ViewBuilder private func passwordStep(_ s: TvAddState) -> some View {
        SettingsTextField(label: MS("servers_username", "Username"), hint: "", text: $username,
                          onSubmit: { setFocus(.passwordField) }, id: "mediaServers.username")
            .focused($focus, equals: .username)
        SettingsTextField(label: MS("servers_password", "Password"), hint: "", text: $password, secure: true,
                          onSubmit: submitPassword, id: "mediaServers.password")
            .focused($focus, equals: .passwordField)
        SettingsHelperText(text: "Your password is sent to the server once and not saved. Only the sign-in it returns is kept, on this device.")
        if let e = s.error { errorLine(e) }
        HStack(spacing: dp(8)) {
            Spacer()
            SettingsDialogButton(title: "Back") { password = ""; model.session.backToChoice() }.focused($focus, equals: .back)
            SettingsDialogButton(title: s.busy ? MS("servers_connecting", "Connecting…") : "Sign in", primary: true) { submitPassword() }
                .focused($focus, equals: .signIn)
                .accessibilityIdentifier("mediaServers.signIn")
                .disabled(s.busy || username.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.top, dp(8))
    }

    private func submitPassword() {
        model.session.submitPassword(username: username, password: password)
        password = ""
    }

    private func errorLine(_ e: TvAddError) -> some View {
        Text(verbatim: errorText(e)).font(NuvioType.bodyMedium).foregroundStyle(colors.error).fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("mediaServers.error")
    }

    private func errorText(_ e: TvAddError) -> String {
        switch e {
        case .invalidAddress: return MS("servers_error_address", "Enter a valid server address.")
        case .notAMediaServer: return "Something answered at that address, but it is not a supported Jellyfin or Emby server."
        case .unreachable: return MS("servers_error_unreachable", "Could not reach the server.")
        case .wrongCredentials: return MS("servers_error_auth", "Incorrect username or password.")
        case .quickConnectFailed: return MS("servers_quick_connect_failed", "Quick Connect did not finish.")
        case .quickConnectExpired: return MS("servers_quick_connect_expired", "This code expired.")
        case .quickConnectOff: return MS("servers_quick_connect_disabled", "Quick Connect is turned off on this server.")
        case .signInFailed: return MS("servers_error_failed", "Could not connect to the server.")
        case .differentServer: return "This address now belongs to a different server than the one you added. Remove the old entry and add the new one."
        case .notSaved: return "The sign-in could not be stored securely on this device."
        case .certificateRefused: return MS("servers_failure_cert_changed", "This server's certificate changed. Sign in again to review it.")
        default: return ""
        }
    }

    private func focusFirst(_ stage: TvAddStage) {
        switch stage {
        case .address: setFocus(.address)
        case .chooseSignIn: setFocus(model.state.quickConnectAvailable ? .quickConnect : .password)
        case .quickConnect: setFocus(.usePassword)
        case .password: setFocus(.username)
        default: break
        }
    }

    private func setFocus(_ target: AddFocus) {
        for delay in [0.05, 0.35] { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } } }
        // The tvOS keyboard (Return on the address field) takes a moment to leave and holds focus until it does: keep
        // asking, but only while focus is still on the field the viewer just left, never against their own navigation.
        for delay in [0.9, 1.8, 3.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus == .address || focus == nil { focus = target } }
        }
    }

    /// Menu steps back one stage (and closes from the first), so the remote never traps the viewer.
    private func back(_ s: TvAddState) {
        switch s.stage {
        case .quickConnect, .password: password = ""; model.session.backToChoice()
        case .chooseSignIn: model.session.backToAddress()
        default: dialogs.pop()
        }
    }
}

@MainActor
final class MediaServerAddModel: ObservableObject {
    let session: TvAddServerSession
    @Published var state: TvAddState
    private var task: Task<Void, Never>?

    init(existingKey: String?) {
        session = TvAddServerSession(existingId: existingKey)
        state = session.state.value
        task = Task { @MainActor [weak self] in
            guard let session = self?.session else { return }
            for await next in session.state { self?.state = next }
        }
    }

    func close() { task?.cancel(); session.close() }
}

// MARK: - One server (full screen)

private enum DetailFocus: Hashable {
    case signIn, library(String), enabled, catalog, watchState, remove
}

struct MediaServerDetailsPage: View {
    let key: String
    @ObservedObject var dialogs: SettingsDialogs
    @Environment(\.nuvio) private var colors
    @StateObject private var model: MediaServerDetailsModel
    @FocusState private var focus: DetailFocus?

    init(key: String, dialogs: SettingsDialogs) {
        self.key = key
        self.dialogs = dialogs
        _model = StateObject(wrappedValue: MediaServerDetailsModel(key: key))
    }

    var body: some View {
        Group {
            if let d = model.details { page(d) } else {
                Text(ui: "This server is no longer on your account.").font(NuvioType.bodyLarge).foregroundStyle(colors.textSecondary)
                    .task { try? await Task.sleep(nanoseconds: 600_000_000); dialogs.pop() }
            }
        }
        .padding(.horizontal, 80).padding(.vertical, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.background.ignoresSafeArea())
        .task { for await next in model.session.details { model.details = next } }
        .task { try? await model.session.refreshLibraries() }
        .onDisappear { model.close() }
        .onExitCommand { dialogs.pop() }
    }

    private func page(_ d: TvServerDetails) -> some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: dp(12)) {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: d.name).font(NuvioType.headlineLarge).foregroundStyle(colors.textPrimary)
                    Spacer()
                    Text(verbatim: d.status.label).font(NuvioType.labelLarge).foregroundStyle(colors.secondary)
                }
                Text(verbatim: d.subtitle).font(NuvioType.bodyMedium).foregroundStyle(colors.textSecondary)
                if d.status == .signInAgain || d.status == .needsSignIn {
                    SettingsHelperText(text: d.status == .signInAgain
                        ? "This device\u{2019}s sign-in ended (the server refused it, or its certificate changed). Sign in again to keep using this server."
                        : "This server came from your account. Sign in once on this device to use it here.")
                    SettingsDialogButton(title: MS("servers_sign_in_again", "Sign in again"), primary: true) {
                        dialogs.pop(); dialogs.push(.mediaServerAdd(key))
                    }
                    .focused($focus, equals: .signIn)
                    .accessibilityIdentifier("mediaServers.signInAgain")
                }
                SettingsGroupCard(title: "Manage") {
                    SettingsToggleRow(title: MS("servers_enabled", "Enabled"),
                                      subtitle: MS("servers_enabled_description", "Show this server in Home, search, and stream results."),
                                      isOn: d.enabled) { model.session.setEnabled(on: !d.enabled) }
                        .focused($focus, equals: .enabled)
                    if d.supportsCatalogMetadata {
                        SettingsToggleRow(title: MS("servers_catalog_metadata", "Use addon metadata"),
                                          subtitle: MS("servers_catalog_metadata_description", "Titles open with your addons' details and sync like any other catalog title, with playback and watched marks still sent to %1$s.", d.providerName),
                                          isOn: d.useCatalogMetadata) { model.session.setCatalogMetadata(on: !d.useCatalogMetadata) }
                            .focused($focus, equals: .catalog)
                    }
                    if d.supportsWatchState {
                        SettingsToggleRow(title: MS("servers_import_watch_state", "Import watch progress"),
                                          subtitle: MS("servers_import_watch_state_description", "Add titles you're watching in %1$s to Continue watching, along with its watched marks.", d.providerName),
                                          isOn: d.importWatchState) { model.session.setImportWatchState(on: !d.importWatchState) }
                            .focused($focus, equals: .watchState)
                    }
                }
                librariesCard(d)
                SettingsGroupCard {
                    SettingsActionRow(title: MS("servers_remove", "Remove server"), showChevron: false) { confirmRemove(d) }
                        .focused($focus, equals: .remove)
                        .accessibilityIdentifier("mediaServers.remove")
                }
            }
            .frame(maxWidth: dp(900), alignment: .topLeading)
        }
        .onAppear { setInitialFocus(d) }
    }

    private func setInitialFocus(_ d: TvServerDetails) {
        let target: DetailFocus = (d.status == .signInAgain || d.status == .needsSignIn) ? .signIn : .enabled
        for delay in [0.05, 0.35] { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } } }
    }

    private func librariesCard(_ d: TvServerDetails) -> some View {
        SettingsGroupCard(title: MS("servers_libraries", "Libraries"),
                          subtitle: d.libraries.isEmpty ? MS("servers_no_libraries", "No movie or series libraries are available to this user.")
                                                        : MS("servers_libraries_description", "Selected libraries appear in home rows, search, and stream results.")) {
            ForEach(d.libraries, id: \.id) { lib in
                SettingsToggleRow(title: lib.name, isOn: lib.selected) { model.session.setLibrarySelected(id: lib.id, on: !lib.selected) }
                    .focused($focus, equals: .library(lib.id))
                    .accessibilityIdentifier("mediaServers.library.\(lib.name)")
            }
        }
    }

    private func confirmRemove(_ d: TvServerDetails) {
        dialogs.push(.custom(AnyView(RemoveServerDialog(name: d.name, dialogs: dialogs) { model.session.remove() })))
    }
}

/// Remove: Cancel has focus first. Signs this device out of the server and drops it from the account's list.
private struct RemoveServerDialog: View {
    let name: String
    @ObservedObject var dialogs: SettingsDialogs
    let action: () -> Void

    var body: some View {
        NuvioDialog(title: MS("servers_remove_confirm_title", "Remove this server?"),
                    subtitle: MS("servers_remove_confirm_message", "This signs you out of %1$s on this device. You can add it back anytime.", name)) {
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: L("Cancel"), initialFocus: true) { dialogs.pop() }
                SettingsDialogButton(title: MS("servers_remove", "Remove server"), destructive: true) {
                    action()
                    // The details page notices the server is gone and closes itself.
                    dialogs.pop()
                }
                .accessibilityIdentifier("mediaServers.removeConfirm")
            }
        }
    }
}

@MainActor
final class MediaServerDetailsModel: ObservableObject {
    let session: TvServerDetailsSession
    @Published var details: TvServerDetails?

    init(key: String) {
        session = TvServerDetailsSession(key: key)
        details = session.details.value
    }

    func close() { session.close() }
}

// MARK: - Library: the "Servers" source

/// One shelf per selected server library (the phone's Library "Servers" view). Titles open Details like any other.
struct ServerLibraryView: View {
    @Environment(\.nuvio) private var colors
    let open: (MetaPreview) -> Void
    @State private var shelves: [TvServerShelf]?

    var body: some View {
        VStack(alignment: .leading, spacing: dp(18)) {
            if let shelves {
                if shelves.isEmpty {
                    NuvioEmptyState(icon: "md_bookmark_border",
                                    title: MS("library_servers_empty_title", "No libraries selected"),
                                    subtitle: "Choose which libraries to show in Settings, under Integrations, then Media servers.")
                }
                ForEach(shelves, id: \.key) { shelf in
                    VStack(alignment: .leading, spacing: dp(8)) {
                        Text(verbatim: shelf.title).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
                        if shelf.failed {
                            Text(verbatim: MS("library_server_load_failed", "Couldn't load %1$s", shelf.title))
                                .font(NuvioType.bodyMedium).foregroundStyle(colors.error)
                        } else {
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: dp(12)) {
                                    ForEach(shelf.items, id: \.id) { item in
                                        NuvioPosterCard(title: item.name, subtitle: item.releaseInfo, imageURL: item.poster,
                                                        width: SearchCardSize.poster.width, height: SearchCardSize.poster.height) { open(item) }
                                    }
                                }
                                .padding(.vertical, dp(8))
                            }
                            .focusSection()
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(dp(24))
            }
        }
        .task { shelves = (try? await TvServerLibrary.shared.shelves()) ?? [] }
    }
}
