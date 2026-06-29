import SwiftUI
import AppKit

/// Manages the menu-bar status item via AppKit so we control the icon's left/right
/// padding (SwiftUI's MenuBarExtra forces a wider, fixed padding).
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    private var defaultsObserver: NSObjectProtocol?

    func applicationDidFinishLaunching(_ notification: Notification) {
        popover.behavior = .transient
        popover.contentSize = NSSize(width: 240, height: 180)
        popover.contentViewController = NSHostingController(
            rootView: MenuBarContent().environmentObject(AppState.shared))

        updateStatusItem()
        defaultsObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.updateStatusItem()
        }
    }

    private func updateStatusItem() {
        let show = (UserDefaults.standard.object(forKey: "showMenuBarIcon") as? Bool) ?? true
        if show, statusItem == nil {
            // variableLength → status item hugs the image; a tightly-drawn template
            // image (no built-in margins like SF Symbols have) keeps padding minimal.
            let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
            if let button = item.button {
                let cfg = NSImage.SymbolConfiguration(pointSize: 14, weight: .semibold)
                if let symbol = NSImage(systemSymbolName: "arrow.up.arrow.down",
                                        accessibilityDescription: "WGFileSender")?
                    .withSymbolConfiguration(cfg) {
                    let trimmed = Self.trimmedHorizontally(symbol)
                    trimmed.isTemplate = true
                    button.image = trimmed
                    // pin the item width tighter than the glyph to shave side padding.
                    item.length = trimmed.size.width - 2
                }
                button.imagePosition = .imageOnly
                button.target = self
                button.action = #selector(togglePopover(_:))
            }
            statusItem = item
        } else if !show, let item = statusItem {
            NSStatusBar.system.removeStatusItem(item)
            statusItem = nil
        }
    }

    /// Crops the transparent left/right margins SF Symbols carry, so the status item
    /// hugs the glyph. Vertical extent and the glyph shape are untouched.
    private static func trimmedHorizontally(_ image: NSImage) -> NSImage {
        let w = Int(image.size.width.rounded()), h = Int(image.size.height.rounded())
        guard w > 0, h > 0,
              let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h,
                  bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                  colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return image }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        image.draw(in: NSRect(x: 0, y: 0, width: w, height: h))
        NSGraphicsContext.restoreGraphicsState()

        var minX = w, maxX = -1
        for x in 0..<w {
            for y in 0..<h where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.05 {
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                break
            }
        }
        guard maxX >= minX else { return image }
        let newW = maxX - minX + 1
        let out = NSImage(size: NSSize(width: newW, height: h))
        out.lockFocus()
        image.draw(at: NSPoint(x: -CGFloat(minX), y: 0),
                   from: NSRect(x: 0, y: 0, width: w, height: h),
                   operation: .sourceOver, fraction: 1)
        out.unlockFocus()
        return out
    }

    @objc private func togglePopover(_ sender: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(sender)
        } else {
            showPopover()
        }
    }

    /// Brings the app forward and opens the menu-bar popover. Used both for the status-item
    /// click and when the app is re-opened (Dock/Finder/"Open with"), which otherwise does
    /// nothing visible for a menu-bar-only app.
    private func showPopover() {
        guard let button = statusItem?.button else { return }
        NSApp.activate(ignoringOtherApps: true)
        if !popover.isShown {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
        }
    }

    /// Re-opening the app (double-click, "Open with", `open`) surfaces the popover.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showPopover()
        return true
    }
}
