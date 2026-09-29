import Foundation
import ObjectiveC
import Security
import TuvoraCore

/// Apple TV's storage chokepoint. The shared code (≈55 iOS storage files) writes everything to
/// `NSUserDefaults.standardUserDefaults`; on tvOS that store warns at 512 KB and terminates the app at
/// 1 MB, and a signed-in simulator session had already reached 686 KB. Rather than fork each file,
/// the standard defaults object is replaced at launch by this subclass, which routes every app key to
/// the tier TvStorageTierPolicy picks: Keychain (tokens, logins, API keys), the real defaults within
/// a 400 KB budget (the durable core), or a file store in Caches (everything rebuildable).
///
/// System keys (framework-owned, capitalised: AppleLanguages, NS…, AV…) pass straight through.
final class TieredUserDefaults: UserDefaults {
    static let shared = TieredUserDefaults(suiteName: nil)!

    private let lock = NSRecursiveLock()
    private let cache = CacheKeyValueStore()
    private let keychain = KeychainKeyValueStore()
    private var tierMemo: [String: StorageTier] = [:]
    private var durableSizes: [String: Int] = [:]
    private var durableTotal: Int { durableSizes.values.reduce(0, +) }

    /// Swaps `+[NSUserDefaults standardUserDefaults]` for `shared`, then migrates existing values.
    static func install() {
        // Create the instance BEFORE the swap: UserDefaults' initialiser consults +standardUserDefaults,
        // which after the swap would re-enter this lazy `shared` and deadlock.
        let instance = shared
        guard let original = class_getClassMethod(UserDefaults.self, #selector(getter: UserDefaults.standard)),
              let replacement = class_getClassMethod(TieredUserDefaults.self, #selector(TieredUserDefaults.tuvoraStandard))
        else { return }
        method_exchangeImplementations(original, replacement)
        instance.migrate()
    }

    @objc class func tuvoraStandard() -> UserDefaults { shared }

    // MARK: routing

    /// UserDefaults' own implementation calls back into the public selectors (removeObject → set(nil)),
    /// which would re-enter the routing below forever. While a call is inside `super`, route nothing.
    private static let passthroughKey = "tuvora.kv.passthrough"
    private var inSuper: Bool { Thread.current.threadDictionary[Self.passthroughKey] != nil }
    private func viaSuper<T>(_ body: () -> T) -> T {
        let dict = Thread.current.threadDictionary
        let outer = dict[Self.passthroughKey] != nil
        dict[Self.passthroughKey] = true
        defer { if !outer { dict.removeObject(forKey: Self.passthroughKey) } }
        return body()
    }

    private func tier(_ key: String) -> StorageTier? {
        guard let first = key.unicodeScalars.first, CharacterSet.lowercaseLetters.contains(first) else { return nil }
        lock.lock(); defer { lock.unlock() }
        if let memo = tierMemo[key] { return memo }
        let tier = TvStorageTierPolicy.shared.tierFor(key: key)
        tierMemo[key] = tier
        return tier
    }

    override func object(forKey key: String) -> Any? {
        if inSuper { return super.object(forKey: key) }
        switch tier(key) {
        case nil: return viaSuper { super.object(forKey: key) }
        case .secure: return keychain.get(key) ?? viaSuper { super.object(forKey: key) }
        case .durable: return viaSuper { super.object(forKey: key) } ?? cache.get(key)
        case .cache: return cache.get(key)
        }
    }

    override func set(_ value: Any?, forKey key: String) {
        if inSuper { super.set(value, forKey: key); return }
        guard let value else { removeObject(forKey: key); return }
        switch tier(key) {
        case nil:
            viaSuper { super.set(value, forKey: key) }
        case .secure:
            if keychain.set(key, value) {
                viaSuper { super.removeObject(forKey: key) }
            } else {
                // Keychain unavailable (e.g. an unsigned simulator build): keep it in the durable core.
                viaSuper { super.set(value, forKey: key) }
            }
        case .durable:
            let size = Self.byteSize(value)
            lock.lock()
            let others = durableTotal - (durableSizes[key] ?? 0)
            let admitted = TvStorageTierPolicy.shared.admitDurable(sizeBytes: Int32(size), otherDurableBytes: Int32(others))
            durableSizes[key] = admitted ? size : nil
            lock.unlock()
            if admitted {
                viaSuper { super.set(value, forKey: key) }
                cache.remove(key)
            } else {
                NSLog("[storage] durable budget: %@ (%d bytes) spills to Caches", key, size)
                cache.set(key, value)
                viaSuper { super.removeObject(forKey: key) }
            }
        case .cache:
            cache.set(key, value)
            viaSuper { super.removeObject(forKey: key) }
        }
    }

    override func removeObject(forKey key: String) {
        if inSuper { super.removeObject(forKey: key); return }
        viaSuper { super.removeObject(forKey: key) }
        guard tier(key) != nil else { return }
        lock.lock(); durableSizes[key] = nil; lock.unlock()
        cache.remove(key)
        keychain.remove(key)
    }

    // Typed accessors all funnel through object/set, so every call site is routed.
    override func set(_ value: Bool, forKey key: String) { set(NSNumber(value: value) as Any?, forKey: key) }
    override func set(_ value: Int, forKey key: String) { set(NSNumber(value: value) as Any?, forKey: key) }
    override func set(_ value: Double, forKey key: String) { set(NSNumber(value: value) as Any?, forKey: key) }
    override func set(_ value: Float, forKey key: String) { set(NSNumber(value: value) as Any?, forKey: key) }
    override func set(_ url: URL?, forKey key: String) { set(url?.absoluteString as Any?, forKey: key) }

    override func string(forKey key: String) -> String? {
        let v = object(forKey: key)
        return v as? String ?? (v as? NSNumber)?.stringValue
    }
    override func data(forKey key: String) -> Data? { object(forKey: key) as? Data }
    override func array(forKey key: String) -> [Any]? { object(forKey: key) as? [Any] }
    override func dictionary(forKey key: String) -> [String: Any]? { object(forKey: key) as? [String: Any] }
    override func stringArray(forKey key: String) -> [String]? { object(forKey: key) as? [String] }
    override func url(forKey key: String) -> URL? { string(forKey: key).flatMap(URL.init(string:)) }
    override func bool(forKey key: String) -> Bool {
        let v = object(forKey: key)
        if let n = v as? NSNumber { return n.boolValue }
        if let s = v as? String { return ["yes", "true", "1"].contains(s.lowercased()) }
        return false
    }
    override func integer(forKey key: String) -> Int {
        let v = object(forKey: key)
        return (v as? NSNumber)?.intValue ?? (v as? String).flatMap { Int($0) } ?? 0
    }
    override func double(forKey key: String) -> Double {
        let v = object(forKey: key)
        return (v as? NSNumber)?.doubleValue ?? (v as? String).flatMap { Double($0) } ?? 0
    }
    override func float(forKey key: String) -> Float { Float(double(forKey: key)) }

    // MARK: migration

    /// Moves every value already in the real defaults to its tier and measures the durable core.
    private func migrate() {
        guard let domain = Bundle.main.bundleIdentifier, let values = viaSuper({ super.persistentDomain(forName: domain) }) else { return }
        var moved = 0
        for (key, value) in values {
            switch tier(key) {
            case nil:
                continue
            case .durable:
                set(value, forKey: key)
            case .secure, .cache:
                set(value, forKey: key)
                moved += 1
            }
        }
        NSLog("[storage] tiered defaults installed: moved %d keys, durable core %d bytes", moved, durableTotal)
    }

    static func byteSize(_ value: Any) -> Int {
        (try? PropertyListSerialization.data(fromPropertyList: value, format: .binary, options: 0).count) ?? 0
    }
}

/// Values in Caches/TuvoraKV, one file per key, mirrored in memory. Purgeable by design.
final class CacheKeyValueStore {
    private let lock = NSLock()
    private var memory: [String: Any] = [:]
    private var missing: Set<String> = []
    private let directory: URL
    private let io = DispatchQueue(label: "tuvora.kv.cache", qos: .utility)

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        directory = caches.appendingPathComponent("TuvoraKV", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func file(_ key: String) -> URL {
        let safe = key.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-" ? String($0) : "%" }.joined()
        return directory.appendingPathComponent(safe + ".plist")
    }

    func get(_ key: String) -> Any? {
        lock.lock(); defer { lock.unlock() }
        if let v = memory[key] { return v }
        if missing.contains(key) { return nil }
        guard let data = try? Data(contentsOf: file(key)),
              let value = try? PropertyListSerialization.propertyList(from: data, format: nil) else {
            missing.insert(key)
            return nil
        }
        memory[key] = value
        return value
    }

    func set(_ key: String, _ value: Any) {
        guard let data = try? PropertyListSerialization.data(fromPropertyList: value, format: .binary, options: 0) else { return }
        lock.lock(); memory[key] = value; missing.remove(key); lock.unlock()
        let url = file(key)
        io.async { try? data.write(to: url, options: .atomic) }
    }

    func remove(_ key: String) {
        lock.lock(); memory[key] = nil; missing.insert(key); lock.unlock()
        let url = file(key)
        io.async { try? FileManager.default.removeItem(at: url) }
    }
}

/// Generic-password Keychain items, one per key, stored as property lists.
final class KeychainKeyValueStore {
    private let service = "com.tuvora.kv"
    /// errSecMissingEntitlement (-34018): an unsigned build has no keychain. Stop trying after the first.
    private var unavailable = false

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }

    func get(_ key: String) -> Any? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? PropertyListSerialization.propertyList(from: data, format: nil)
    }

    @discardableResult
    func set(_ key: String, _ value: Any) -> Bool {
        if unavailable { return false }
        guard let data = try? PropertyListSerialization.data(fromPropertyList: value, format: .binary, options: 0) else { return false }
        let update: [String: Any] = [kSecValueData as String: data]
        var status = SecItemUpdate(query(key) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(key)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status == errSecMissingEntitlement {
            unavailable = true
            NSLog("[storage] keychain unavailable (unsigned build); secure keys stay in the durable core")
        } else if status != errSecSuccess {
            NSLog("[storage] keychain write %@ failed: %d", key, status)
        }
        return status == errSecSuccess
    }

    func remove(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}
