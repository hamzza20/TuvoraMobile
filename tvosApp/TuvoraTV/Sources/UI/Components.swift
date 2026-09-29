import SwiftUI

/// A list row: visibly highlighted when focused (D-pad focus must always show), marigold when selected.
struct RowButtonStyle: ButtonStyle {
    let selected: Bool
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 24).padding(.vertical, 14)
            .foregroundStyle(focused ? Color.black : (selected ? Theme.accent : Color.white))
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(focused ? Color.white : (selected ? Theme.surfaceFocused : Theme.surface))
            )
            .scaleEffect(focused ? 1.03 : (configuration.isPressed ? 0.98 : 1))
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

struct ChipButtonStyle: ButtonStyle {
    let selected: Bool
    @Environment(\.isFocused) private var focused

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.callout.weight(.semibold))
            .padding(.horizontal, 28).padding(.vertical, 12)
            .foregroundStyle(focused ? Color.black : (selected ? Color.black : Color.white))
            .background(Capsule().fill(focused ? Color.white : (selected ? Theme.accent : Theme.surface)))
            .scaleEffect(focused ? 1.06 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

struct EmptyStateView: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 16) {
            Text(title).font(.title2).bold()
            Text(message).font(.body).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center).frame(maxWidth: 900)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ErrorStateView: View {
    let title: String
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text(title).font(.title2).bold()
            Text(message).font(.body).foregroundStyle(Theme.secondaryText).multilineTextAlignment(.center).frame(maxWidth: 900)
            Button("Try again", action: retry)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
