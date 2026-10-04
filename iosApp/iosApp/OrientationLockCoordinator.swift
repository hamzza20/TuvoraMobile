import UIKit
import UserNotifications
import ComposeApp

private let lockPlayerToLandscapeNotification = Notification.Name("NuvioPlayerLockLandscape")
private let lockPlayerToPortraitNotification = Notification.Name("NuvioPlayerLockPortrait")
private let unlockPlayerOrientationNotification = Notification.Name("NuvioPlayerUnlockOrientation")

final class OrientationLockAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        OrientationLockCoordinator.shared.start()
        DownloadsLiveActivityManager.shared.start()
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLockCoordinator.shared.supportedOrientations
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        DownloadsPlatformDownloader_iosKt.handleDownloadsBackgroundEvents(
            identifier: identifier,
            completionHandler: completionHandler
        )
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        DownloadsPlatformDownloader_iosKt.pauseDownloadsForAppBackground()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let deepLink = response.notification.request.content.userInfo["deeplink"] as? String {
            AppUrlBridgeKt.handleAppUrl(url: deepLink)
        }
        completionHandler()
    }
}

final class OrientationLockCoordinator {
    static let shared = OrientationLockCoordinator()

    private(set) var supportedOrientations: UIInterfaceOrientationMask = .allButUpsideDown
    private var observers: [NSObjectProtocol] = []
    /// How the interface faced before a player lock took over; restored on release when the
    /// device can't say how it is held (flat, unknown). Nil while no lock is active.
    private var interfacePostureBeforeLock: OrientationPosture?
    /// Where the last release is rotating the interface to, for a lock posted in the same main-queue
    /// turn (Live TV mode switch = unlock + lock). Cleared on the next turn so it never goes stale.
    private var releaseSettlingPosture: OrientationPosture?

    private init() {}

    func start() {
        guard observers.isEmpty else { return }

        // Device orientation is only reported while generation is on (nesting-counted, cheap).
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        let center = NotificationCenter.default
        observers.append(
            center.addObserver(forName: lockPlayerToLandscapeNotification, object: nil, queue: .main) { [weak self] _ in
                self?.setLandscapeLock(enabled: true)
            }
        )
        observers.append(
            center.addObserver(forName: lockPlayerToPortraitNotification, object: nil, queue: .main) { [weak self] _ in
                self?.rememberPreLockPosture()
                self?.setForcedOrientation(.portrait, forceRotate: true)
            }
        )
        observers.append(
            center.addObserver(forName: unlockPlayerOrientationNotification, object: nil, queue: .main) { [weak self] _ in
                self?.releaseLock()
            }
        )
    }

    private func setLandscapeLock(enabled: Bool) {
        if enabled {
            rememberPreLockPosture()
            setForcedOrientation(.landscape, forceRotate: true)
        } else {
            releaseLock()
        }
    }

    private func rememberPreLockPosture() {
        // Only the first lock in a chain counts (landscape -> portrait switches inside Live TV).
        guard interfacePostureBeforeLock == nil else { return }
        interfacePostureBeforeLock = OrientationReleasePolicy.shared.preLockPosture(
            interfaceNow: posture(of: currentInterfaceOrientation),
            settlingTo: releaseSettlingPosture
        )
    }

    /// B107: widening the mask alone never rotates back — iOS waits for the device to move, so an
    /// upright phone stayed sideways after the player. Rotate to how the phone is held instead.
    private func releaseLock() {
        let before = interfacePostureBeforeLock ?? .unknown
        interfacePostureBeforeLock = nil
        supportedOrientations = .allButUpsideDown

        let interfaceNow = posture(of: currentInterfaceOrientation)
        let target = OrientationReleasePolicy.shared.target(
            device: posture(of: UIDevice.current.orientation),
            interfaceNow: interfaceNow,
            interfaceBeforeLock: before
        )
        releaseSettlingPosture = OrientationReleasePolicy.shared.settledPosture(target: target, interfaceNow: interfaceNow)
        DispatchQueue.main.async { [weak self] in self?.releaseSettlingPosture = nil }
        let targetMask: UIInterfaceOrientationMask?
        switch target {
        case .portrait: targetMask = .portrait
        case .landscape: targetMask = preferredLandscapeOrientation == .landscapeLeft ? .landscapeLeft : .landscapeRight
        default: targetMask = nil
        }

        if #available(iOS 16.0, *) {
            // Order matters: refresh the supported set first, then request the geometry.
            updateSupportedOrientationsOnRootControllers()
            if let targetMask {
                requestGeometry(targetMask)
            }
        } else {
            if let targetMask {
                let orientation: UIInterfaceOrientation = targetMask == .portrait ? .portrait : preferredLandscapeOrientation
                UIDevice.current.setValue(orientation.rawValue, forKey: "orientation")
            }
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private var currentInterfaceOrientation: UIInterfaceOrientation {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })?
            .interfaceOrientation
            ?? UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first
            ?? .unknown
    }

    private func posture(of orientation: UIInterfaceOrientation) -> OrientationPosture {
        if orientation.isPortrait { return .portrait }
        if orientation.isLandscape { return .landscape }
        return .unknown
    }

    private func posture(of orientation: UIDeviceOrientation) -> OrientationPosture {
        switch orientation {
        case .portrait: return .portrait
        case .landscapeLeft, .landscapeRight: return .landscape
        // Upside-down is not a supported interface orientation; face up/down and unknown carry
        // no hint of how the phone will be held.
        default: return .unknown
        }
    }

    @available(iOS 16.0, *)
    private func updateSupportedOrientationsOnRootControllers() {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .forEach { window in
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
    }

    @available(iOS 16.0, *)
    private func requestGeometry(_ mask: UIInterfaceOrientationMask) {
        let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .forEach { scene in
                scene.requestGeometryUpdate(preferences) { error in
                    print("[OrientationLockCoordinator] Geometry update failed: \(error.localizedDescription)")
                }
            }
    }

    private func setForcedOrientation(_ mask: UIInterfaceOrientationMask, forceRotate: Bool) {
        supportedOrientations = mask
        requestOrientationUpdate(for: mask, forceRotate: forceRotate)
    }

    private func requestOrientationUpdate(for mask: UIInterfaceOrientationMask, forceRotate: Bool) {
        if #available(iOS 16.0, *) {
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .forEach { scene in
                    scene.requestGeometryUpdate(preferences) { error in
                        print("[OrientationLockCoordinator] Geometry update failed: \(error.localizedDescription)")
                    }
                }
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .forEach { window in
                    window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                }
        } else {
            if forceRotate {
                let target: UIInterfaceOrientation = mask.contains(.portrait)
                    ? .portrait
                    : preferredLandscapeOrientation
                UIDevice.current.setValue(target.rawValue, forKey: "orientation")
            } else if UIDevice.current.orientation.isPortrait {
                UIDevice.current.setValue(UIInterfaceOrientation.portrait.rawValue, forKey: "orientation")
            }
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private var preferredLandscapeOrientation: UIInterfaceOrientation {
        if let sceneOrientation = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.interfaceOrientation })
            .first(where: \.isLandscape) {
            return sceneOrientation
        }

        switch UIDevice.current.orientation {
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return .landscapeRight
        }
    }
}
