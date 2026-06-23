// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "WGFileSender",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "WGFileSender",
            path: "Sources/WGFileSender"
        )
    ]
)
