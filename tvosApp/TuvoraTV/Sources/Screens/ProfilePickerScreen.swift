import SwiftUI
import TuvoraCore

/// Minimal "Who's watching?" so the gate can be driven end to end. Phase 3 replaces it with the full
/// picker (avatars, PIN entry, add profile); PIN-locked profiles are shown but not selectable yet.
struct ProfilePickerView: View {
    @State private var profiles: [NuvioProfile] = []

    var body: some View {
        VStack(spacing: 40) {
            Text("Who's watching?").font(.largeTitle)
            if profiles.isEmpty {
                ProgressView("Loading profiles")
            } else {
                HStack(spacing: 32) {
                    ForEach(profiles, id: \.profileIndex) { profile in
                        Button {
                            NSLog("SMOKE pick profile=%d", profile.profileIndex)
                            TvAppLifecycle.shared.pickProfile(profileIndex: profile.profileIndex)
                        } label: {
                            Text(profile.pinEnabled ? "\(profile.name) 🔒" : profile.name).font(.title2)
                        }
                        .disabled(profile.pinEnabled)
                    }
                }
            }
        }
        .task {
            for await state in ProfileRepository.shared.state {
                profiles = state.profiles
                // Simulator smoke hook: `-smokePickProfile <index>` picks without a remote.
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-smokePickProfile"), i + 1 < args.count, let index = Int32(args[i + 1]),
                   state.profiles.contains(where: { $0.profileIndex == index }) {
                    TvAppLifecycle.shared.pickProfile(profileIndex: index)
                }
                NSLog("SMOKE profiles=%@", state.profiles.map { "\($0.profileIndex):\($0.pinEnabled ? "pin" : "open")" }.joined(separator: ","))
            }
        }
    }
}
