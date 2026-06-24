import Foundation

/// Async semaphore: caps how many tasks run at once while still letting each task
/// be cancelled independently (unlike a TaskGroup, which only cancels as a whole).
actor ConcurrencyLimiter {
    private let limit: Int
    private var inUse = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ limit: Int) { self.limit = limit }

    func acquire() async {
        if inUse < limit { inUse += 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if !waiters.isEmpty {
            waiters.removeFirst().resume()
        } else {
            inUse = max(0, inUse - 1)
        }
    }
}
