import SwiftUI
import TuvoraCore

// Settings -> Integrations -> Media servers (Jellyfin / Emby as sources), Apple TV.
//
//   LIST      "Add a server" + the saved servers, each with a status badge (Signed in / Sign in again / ...).
//   ADD       one dialog, steps: address -> (certificate fingerprint) -> name + sign-in choice ->
//             Quick Connect code OR username + password -> "Show Recently added on Home?".
//   DETAILS   a full-screen page: Home rows (off by default), libraries, Manage (rename, on/off, address
//             sync, sign out, remove).
//
// Every decision lives in the shared Kotlin (the same controllers the phone's pages drive); this file only
// draws them. Typing on a TV remote is painful, so the Quick Connect code is offered first, as Swiftfin's
// tvOS sign-in does. There is no "approve a code" here: Swiftfin only offers that on iPhone/iPad.
// Text comes from the shared string tables (ms_* keys) so it is localised like every other Apple TV string.

/// Looks an `ms_*` string up in the shared Tuvora.strings table (Android placeholders: %1$s, %s).
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
        case .signedIn: return MS("ms_status_signed_in", "Signed in")
        case .signInAgain: return MS("ms_status_sign_in_again", "Sign in again")
        case .needsSignIn: return MS("ms_status_needs_sign_in", "Sign in on this device")
        case .offline: return MS("ms_status_offline", "Offline")
        case .disabled: return MS("ms_status_disabled", "Off")
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
            SettingsDetailHeader(title: MS("ms_settings_page_servers", "Media servers"),
                                 subtitle: MS("ms_settings_integrations_description", "Movies and series from your own Jellyfin or Emby server"))
            SettingsGroupCard {
                SettingsActionRow(title: MS("ms_add_row_title", "Add a server"),
                                  subtitle: MS("ms_add_row_description", "Jellyfin or Emby, on your network or online"),
                                  leadingIcon: "md_add") {
                    dialogs.push(.mediaServerAdd(nil))
                }
                .accessibilityIdentifier("mediaServers.add")
                ForEach(rows, id: \.key) { row in
                    SettingsActionRow(title: row.name, subtitle: row.subtitle,
                                      value: row.checking && row.status == .signedIn ? MS("ms_status_checking", "Checking...") : row.status.label,
                                      valueColor: row.status == .signInAgain || row.status == .needsSignIn ? nil : nil) {
                        // A server that needs this device's sign-in goes straight to sign-in; the rest to details.
                        dialogs.push(.mediaServerDetails(row.key))
                    }
                    .accessibilityIdentifier("mediaServers.row.\(row.name)")
                }
            }
            if rows.isEmpty {
                SettingsHelperText(text: MS("ms_empty_body", "No servers yet. Add your own Jellyfin or Emby server to browse and play it here. Your sign-in stays on this device."))
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
    case address, connect, type(String), name, quickConnect, password, changeAddress
    case user(Int), username, passwordField, signIn, back, trust, decline, yes, no, usePassword
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
            case .offerHomeRow: offerStep(s)
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
        // cancellation cancels the Kotlin coroutine (nothing polls in the background).
        .task(id: PollKey(stage: s.stage, active: scenePhase == .active)) {
            guard s.stage == .quickConnect, scenePhase == .active else { return }
            try? await model.session.runQuickConnect()
        }
        .task(id: s.quickConnectStartedAtMs) {
            while !Task.isCancelled && s.stage == .quickConnect {
                now = Date()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private struct PollKey: Hashable { let stage: TvAddStage; let active: Bool }

    private func title(_ s: TvAddState) -> String {
        switch s.stage {
        case .quickConnect: return MS("ms_add_qc_title", "Enter this code to sign in")
        case .offerHomeRow: return MS("ms_add_rows_title", "Show Recently added on Home?")
        case .password: return MS("ms_add_username_label", "Username")
        default: return existingKey != nil ? MS("ms_settings_page_details", "Server") : MS("ms_settings_page_add", "Add a server")
        }
    }

    private func subtitle(_ s: TvAddState) -> String? {
        switch s.stage {
        case .address: return MS("ms_add_intro", "Type your server\u{2019}s address. Tuvora tries the usual ports and https or http for you.")
        case .chooseSignIn:
            guard let name = s.foundName, let product = s.foundProduct else { return nil }
            return MS("ms_add_connected", "Connected to %1$s (%2$s)", name, product)
        case .quickConnect:
            return MS("ms_add_qc_steps", "On a device already signed in to %1$s, open Quick Connect and enter this code. On Jellyfin: Settings, then Quick Connect.", s.foundName ?? "")
        case .offerHomeRow:
            return MS("ms_add_rows_body", "%1$s\u{2019}s newest movies and shows get a row of their own on Home. You can change this any time in the server\u{2019}s settings.", s.signedInName ?? "")
        default: return nil
        }
    }

    // Step 1: the address (tvOS native keyboard) and the product.
    @ViewBuilder private func addressStep(_ s: TvAddState) -> some View {
        if existingKey == nil {
            HStack(spacing: dp(8)) {
                typeChip(MS("ms_add_type_auto", "Detect"), wire: nil, selected: s.selectedType == nil)
                typeChip("Jellyfin", wire: "jellyfin", selected: s.selectedType == "jellyfin")
                typeChip("Emby", wire: "emby", selected: s.selectedType == "emby")
                Spacer()
            }
        }
        SettingsTextField(label: MS("ms_add_address_label", "Server address"),
                          hint: MS("ms_add_address_hint", "192.168.1.20:8096 or media.example.com"),
                          text: Binding(get: { model.state.address }, set: { model.session.setAddress(text: $0) }),
                          onSubmit: { model.session.connect() }, id: "mediaServers.address")
            .focused($focus, equals: .address)
        if let e = s.error { errorLine(e) }
        if let cert = s.certAuthority, let fp = s.certFingerprint { certificate(cert, fp) }
        HStack(spacing: dp(8)) {
            Spacer()
            SettingsDialogButton(title: L("Cancel")) { dialogs.pop() }
            SettingsDialogButton(title: s.busy ? MS("ms_add_checking", "Checking...") : MS("ms_add_connect", "Connect"), primary: true) {
                model.session.connect()
            }
            .focused($focus, equals: .connect)
            .accessibilityIdentifier("mediaServers.connect")
            .disabled(s.busy || s.address.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.top, dp(8))
    }

    private func typeChip(_ label: String, wire: String?, selected: Bool) -> some View {
        SettingsChoiceChip(label: label, selected: selected) { model.session.selectType(wire: wire) }
            .focused($focus, equals: .type(wire ?? "auto"))
    }

    /// The certificate the system did not trust: shown with its fingerprint (trust on first use).
    private func certificate(_ authority: String, _ fingerprint: String) -> some View {
        VStack(alignment: .leading, spacing: dp(8)) {
            Text(ui: MS("ms_add_cert_title", "Trust this certificate?")).font(NuvioType.titleMedium).foregroundStyle(colors.textPrimary)
            Text(ui: MS("ms_add_cert_body", "This server\u{2019}s certificate was not issued by an authority your device trusts. If this fingerprint matches your server, trust it. Tuvora will warn you if it ever changes."))
                .font(NuvioType.bodySmall).foregroundStyle(colors.textSecondary)
            Text(ui: MS("ms_add_cert_fingerprint", "Fingerprint")).font(NuvioType.labelMedium).foregroundStyle(colors.textTertiary)
            Text(verbatim: fingerprint).font(.system(size: dp(13), design: .monospaced)).foregroundStyle(colors.textPrimary)
            Text(verbatim: authority).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: L("Cancel")) { model.session.declineCertificate() }.focused($focus, equals: .decline)
                SettingsDialogButton(title: MS("ms_add_cert_trust", "Trust"), primary: true) { model.session.trustCertificate() }
                    .focused($focus, equals: .trust)
                    .accessibilityIdentifier("mediaServers.trust")
            }
        }
        .padding(dp(14))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
    }

    // Step 2: name + how to sign in. Quick Connect first (typing a password with a remote is the slow way).
    @ViewBuilder private func chooseStep(_ s: TvAddState) -> some View {
        if let correction = s.typeCorrection {
            let parts = correction.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            SettingsHelperText(text: MS("ms_add_type_corrected", "You picked %1$s, but this server is %2$s. Continuing as %2$s.", parts.first ?? "", parts.last ?? ""))
        }
        if !s.signingInExisting {
            SettingsTextField(label: MS("ms_add_name_label", "Server name"), hint: MS("ms_add_name_hint", "Shown in Tuvora; you can change it later"),
                              text: Binding(get: { model.state.serverName }, set: { model.session.setServerName(text: $0) }), id: "mediaServers.name")
                .focused($focus, equals: .name)
        }
        if s.quickConnectAvailable {
            SettingsActionRow(title: MS("ms_add_quick_connect_row", "Quick Connect"),
                              subtitle: MS("ms_add_quick_connect_row_description", "Get a code and approve it from a device that is already signed in")) {
                model.session.startQuickConnect()
            }
            .focused($focus, equals: .quickConnect)
            .accessibilityIdentifier("mediaServers.quickConnect")
        }
        SettingsActionRow(title: MS("ms_add_password_row", "Username and password"),
                          subtitle: MS("ms_add_password_row_description", "Your password is used once to sign in and is never stored")) {
            model.session.usePassword()
        }
        .focused($focus, equals: .password)
        .accessibilityIdentifier("mediaServers.usePassword")
        if let e = s.error { errorLine(e) }
        SettingsActionRow(title: MS("ms_add_change_address", "Use a different address"), showChevron: false) { model.session.backToAddress() }
            .focused($focus, equals: .changeAddress)
    }

    // Quick Connect: the code, big, with the countdown (Swiftfin shows the same: steps + a tracked code).
    @ViewBuilder private func quickConnectStep(_ s: TvAddState) -> some View {
        VStack(spacing: dp(10)) {
            if let code = s.quickConnectCode {
                Text(verbatim: code).font(.system(size: dp(54), weight: .bold, design: .monospaced)).tracking(dp(6))
                    .foregroundStyle(colors.textPrimary).accessibilityIdentifier("mediaServers.qcCode")
                Text(verbatim: MS("ms_add_qc_expires", "Expires in %1$s",
                                  model.session.countdownLabel(startedAtMs: s.quickConnectStartedAtMs, nowMs: Int64(now.timeIntervalSince1970 * 1000))))
                    .font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
                Text(ui: MS("ms_add_qc_waiting", "Waiting for approval...")).font(NuvioType.bodySmall).foregroundStyle(colors.textTertiary)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, dp(18))
        .background(RoundedRectangle(cornerRadius: dp(12)).fill(colors.backgroundCard))
        SettingsActionRow(title: MS("ms_add_use_password", "Use username and password instead"), showChevron: false) { model.session.usePassword() }
            .focused($focus, equals: .usePassword)
        SettingsActionRow(title: MS("ms_add_back", "Back"), showChevron: false) { model.session.backToChoice() }
            .focused($focus, equals: .back)
    }

    // Password: the server's public users as chips (a hidden user types the name), then the native keyboard.
    @ViewBuilder private func passwordStep(_ s: TvAddState) -> some View {
        if !s.publicUsers.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: dp(8)) {
                    ForEach(Array(s.publicUsers.enumerated()), id: \.offset) { i, name in
                        SettingsChoiceChip(label: name, selected: username == name) { username = name }.focused($focus, equals: .user(i))
                    }
                }
                .padding(.vertical, dp(4))
            }
        }
        SettingsTextField(label: MS("ms_add_username_label", "Username"), hint: "", text: $username, id: "mediaServers.username")
            .focused($focus, equals: .username)
        SettingsTextField(label: MS("ms_add_password_label", "Password"), hint: "", text: $password, secure: true,
                          onSubmit: submitPassword, id: "mediaServers.password")
            .focused($focus, equals: .passwordField)
        SettingsHelperText(text: MS("ms_add_password_note", "Your password is sent to the server once and not saved. Only the sign-in it returns is kept, on this device."))
        if let e = s.error { errorLine(e) }
        HStack(spacing: dp(8)) {
            Spacer()
            SettingsDialogButton(title: MS("ms_add_back", "Back")) { password = ""; model.session.backToChoice() }.focused($focus, equals: .back)
            SettingsDialogButton(title: s.busy ? MS("ms_add_signing_in", "Signing in...") : MS("ms_add_sign_in", "Sign in"), primary: true) { submitPassword() }
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

    @ViewBuilder private func offerStep(_ s: TvAddState) -> some View {
        HStack(spacing: dp(8)) {
            Spacer()
            SettingsDialogButton(title: MS("ms_add_rows_no", "Not now")) { model.session.skipHomeRowOffer() }.focused($focus, equals: .no)
            SettingsDialogButton(title: MS("ms_add_rows_yes", "Show on Home"), primary: true) { model.session.enableRecentlyAdded() }
                .focused($focus, equals: .yes)
                .accessibilityIdentifier("mediaServers.showOnHome")
        }
    }

    private func errorLine(_ e: TvAddError) -> some View {
        Text(verbatim: errorText(e)).font(NuvioType.bodyMedium).foregroundStyle(colors.error).fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("mediaServers.error")
    }

    private func errorText(_ e: TvAddError) -> String {
        switch e {
        case .invalidAddress: return MS("ms_add_error_invalid_address", "That does not look like a server address.")
        case .notAMediaServer: return MS("ms_add_error_not_a_server", "Something answered at that address, but it is not a Jellyfin or Emby server.")
        case .unreachable: return MS("ms_add_error_unreachable", "Could not reach the server. Check the address, the port and that the server is on.")
        case .wrongCredentials: return MS("ms_add_error_wrong_credentials", "Wrong username or password.")
        case .quickConnectFailed: return MS("ms_add_error_quick_connect", "Quick Connect did not finish. It may be turned off on the server.")
        case .signInFailed: return MS("ms_add_error_sign_in_failed", "Could not sign in. Try again in a moment.")
        case .differentServer: return MS("ms_add_error_different_server", "This address now belongs to a different server than the one you added. Remove the old entry and add the new one.")
        case .notSaved: return MS("ms_add_error_not_saved", "The sign-in could not be stored securely on this device.")
        case .unusableServer: return MS("ms_add_error_unusable", "This server reported an identity Tuvora cannot use.")
        default: return ""
        }
    }

    private func focusFirst(_ stage: TvAddStage) {
        switch stage {
        case .address: setFocus(.address)
        case .chooseSignIn: setFocus(model.state.quickConnectAvailable ? .quickConnect : .password)
        case .quickConnect: setFocus(.usePassword)
        case .password: setFocus(model.state.publicUsers.isEmpty ? .username : .user(0))
        case .offerHomeRow: setFocus(.yes)
        default: break
        }
    }

    private func setFocus(_ target: AddFocus) {
        for delay in [0.05, 0.35] { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } } }
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
        session = TvAddServerSession(existingKey: existingKey)
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
    case signIn, home(String), library(String), name, enabled, sync, signOut, remove
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
                Text(ui: MS("ms_details_gone", "This server is no longer on your account.")).font(NuvioType.bodyLarge).foregroundStyle(colors.textSecondary)
                    .task { try? await Task.sleep(nanoseconds: 600_000_000); dialogs.pop() }
            }
        }
        .padding(.horizontal, 80).padding(.vertical, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.background.ignoresSafeArea())
        .task { await model.observe() }
        .task { try? await model.session.loadLibraries() }
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
                        ? MS("ms_details_sign_in_hint_again", "This device\u{2019}s sign-in ended (the server refused it). Sign in again to keep using this server.")
                        : MS("ms_details_sign_in_hint_new", "This server came from your account. Sign in once on this device to use it here."))
                    SettingsDialogButton(title: MS("ms_details_sign_in", "Sign in"), primary: true) {
                        dialogs.pop(); dialogs.push(.mediaServerAdd(key))
                    }
                    .focused($focus, equals: .signIn)
                    .accessibilityIdentifier("mediaServers.signInAgain")
                }
                SettingsGroupCard(title: MS("ms_details_section_home", "On Home"),
                                  // Not the shared hint: its last sentence points at phone settings (Appearance, Homescreen) that Apple TV does not have.
                                  subtitle: "Tuvora's own Continue Watching already includes what you play from this server. These are the server's own shelves, off until you turn them on.") {
                    homeToggle("continue_watching", MS("ms_details_home_continue", "Server\u{2019}s Continue Watching"), d.homeContinueWatching)
                    homeToggle("next_up", MS("ms_details_home_next_up", "Server\u{2019}s Next Up"), d.homeNextUp)
                    homeToggle("recently_added", MS("ms_details_home_recent", "Recently added"), d.homeRecentlyAdded)
                }
                if d.canListLibraries { librariesCard }
                SettingsGroupCard(title: MS("ms_details_section_manage", "Manage")) {
                    SettingsActionRow(title: MS("ms_details_name", "Name"), value: d.name) { rename(d) }
                        .focused($focus, equals: .name)
                    SettingsToggleRow(title: MS("ms_details_enabled", "Use this server"),
                                      subtitle: MS("ms_details_enabled_hint", "Turn off to hide it from Home and search without removing it"),
                                      isOn: d.enabled) { model.session.setEnabled(on: !d.enabled) }
                        .focused($focus, equals: .enabled)
                    // The plain-language reason tokens never sync (design D1/D7), shown where the switch is.
                    SettingsToggleRow(title: MS("ms_details_sync_address", "Sync address with your account"),
                                      subtitle: MS("ms_details_sync_address_hint", "Lets your other devices offer to sign in to this server. Only the address and username sync. Passwords and sign-in tokens never leave this device, so each device signs in on its own."),
                                      isOn: d.syncAddress) { model.session.setSyncAddress(on: !d.syncAddress) }
                        .focused($focus, equals: .sync)
                    if d.signedIn {
                        SettingsActionRow(title: MS("ms_details_sign_out", "Sign out on this device"), showChevron: false) { confirmSignOut(d) }
                            .focused($focus, equals: .signOut)
                            .accessibilityIdentifier("mediaServers.signOut")
                    }
                    SettingsActionRow(title: MS("ms_details_remove", "Remove server"), showChevron: false) { confirmRemove(d) }
                        .focused($focus, equals: .remove)
                        .accessibilityIdentifier("mediaServers.remove")
                }
            }
            .frame(maxWidth: dp(900), alignment: .topLeading)
        }
        .onAppear { setInitialFocus(d) }
    }

    private func setInitialFocus(_ d: TvServerDetails) {
        let target: DetailFocus = (d.status == .signInAgain || d.status == .needsSignIn) ? .signIn : .home("continue_watching")
        for delay in [0.05, 0.35] { DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if focus != target { focus = target } } }
    }

    private func homeToggle(_ row: String, _ title: String, _ on: Bool) -> some View {
        SettingsToggleRow(title: title, isOn: on) { model.session.setHomeRow(row: row, on: !on) }
            .focused($focus, equals: .home(row))
            .accessibilityIdentifier("mediaServers.home.\(row)")
    }

    private var librariesCard: some View {
        SettingsGroupCard(title: MS("ms_details_section_libraries", "Libraries")) {
            switch model.libraries.state {
            case .loading: ProgressView().frame(maxWidth: .infinity).padding(dp(10))
            case .empty: SettingsHelperText(text: MS("ms_details_libraries_empty", "No movie or series libraries found."))
            case .failed: SettingsHelperText(text: MS("ms_details_libraries_failed", "Could not load the libraries right now."))
            default:
                ForEach(model.libraries.libraries, id: \.id) { lib in
                    SettingsToggleRow(title: lib.name, subtitle: MS("ms_details_libraries_hint", "Show as a row on Home"), isOn: lib.onHome) {
                        model.session.setHomeLibrary(id: lib.id, name: lib.name, on: !lib.onHome)
                        model.session.refreshLibraryFlags()
                    }
                    .focused($focus, equals: .library(lib.id))
                }
            }
        }
    }

    private func rename(_ d: TvServerDetails) {
        dialogs.push(.custom(AnyView(RenameServerDialog(initial: d.name, dialogs: dialogs) { model.session.rename(name: $0) })))
    }

    private func confirmSignOut(_ d: TvServerDetails) {
        dialogs.push(.custom(AnyView(
            NuvioDialog(title: MS("ms_details_sign_out_title", "Sign out?"),
                        subtitle: MS("ms_details_sign_out_body", "This device will be signed out of %1$s. The server stays in your list.", d.name)) {
                HStack(spacing: dp(8)) {
                    Spacer()
                    SettingsDialogButton(title: L("Cancel"), initialFocus: true) { dialogs.pop() }
                    SettingsDialogButton(title: MS("ms_details_sign_out_confirm", "Sign out"), destructive: true) {
                        dialogs.pop()
                        Task { try? await model.session.signOut() }
                    }
                }
            })))
    }

    private func confirmRemove(_ d: TvServerDetails) {
        dialogs.push(.custom(AnyView(RemoveServerDialog(name: d.name, dialogs: dialogs) { purge in
            let removed = (try? await model.session.remove(purgeSavedData: purge))?.boolValue ?? false
            return removed
        })))
    }
}

private struct RenameServerDialog: View {
    let initial: String
    @ObservedObject var dialogs: SettingsDialogs
    let onSave: (String) -> Void
    @State private var text = ""

    var body: some View {
        NuvioDialog(title: MS("ms_details_rename_title", "Rename server"), width: dp(560)) {
            SettingsTextField(label: MS("ms_details_name", "Name"), hint: initial, text: $text, onSubmit: save)
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: L("Cancel")) { dialogs.pop() }
                SettingsDialogButton(title: L("Save"), primary: true) { save() }
            }
            .padding(.top, dp(8))
        }
        .onAppear { text = initial }
    }

    private func save() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { onSave(trimmed) }
        dialogs.pop()
    }
}

/// Remove: Cancel has focus first; the optional purge switch is off by default (nothing on the server changes).
private struct RemoveServerDialog: View {
    let name: String
    @ObservedObject var dialogs: SettingsDialogs
    let action: (Bool) async -> Bool
    @State private var purge = false
    @State private var working = false

    var body: some View {
        NuvioDialog(title: MS("ms_details_remove_title", "Remove server?"),
                    subtitle: MS("ms_details_remove_body", "%1$s will be removed from your account and signed out on this device. Nothing on the server itself changes.", name)) {
            SettingsToggleRow(title: MS("ms_details_remove_purge", "Also clear its Continue Watching and library items"), isOn: purge) { purge.toggle() }
            HStack(spacing: dp(8)) {
                Spacer()
                SettingsDialogButton(title: L("Cancel"), initialFocus: true) { if !working { dialogs.pop() } }
                SettingsDialogButton(title: MS("ms_details_remove_confirm", "Remove"), destructive: true) {
                    guard !working else { return }
                    working = true
                    Task {
                        _ = await action(purge)
                        // The details page notices the entry is gone and closes itself.
                        dialogs.pop()
                    }
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
    @Published var libraries = TvLibraries(state: .loading, libraries: [])

    init(key: String) {
        session = TvServerDetailsSession(key: key)
        details = session.details.value
    }

    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in for await d in self.session.details { self.details = d } }
            group.addTask { @MainActor in for await l in self.session.libraries { self.libraries = l } }
        }
    }

    func close() { session.close() }
}
