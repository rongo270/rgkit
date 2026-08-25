// swift-tools-version:5.9
import PackageDescription

// SPM only reads a manifest from the repository root, so the iOS SDKs are
// exposed as products of one package here rather than from their own
// subdirectories. Each SDK also keeps a standalone Package.swift next to its
// sources for building/testing that SDK on its own; this file is the one
// consumers resolve.
//
//   .package(url: "https://github.com/rongo270/rgkit.git", from: "0.2.0")
//   .product(name: "UserMemory", package: "rgkit")
//
// Platform floor is the strictest of the two SDKs (UserMemory needs macOS 13).
let package = Package(
    name: "rgkit",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
    ],
    products: [
        .library(name: "UserMemory", targets: ["UserMemory"]),
        .library(name: "FeatureUsage", targets: ["FeatureUsage"]),
    ],
    targets: [
        .target(
            name: "UserMemory",
            path: "user-memory/ios/UserMemory/Sources/UserMemory"
        ),
        .testTarget(
            name: "UserMemoryTests",
            dependencies: ["UserMemory"],
            path: "user-memory/ios/UserMemory/Tests/UserMemoryTests"
        ),
        .target(
            name: "FeatureUsage",
            path: "feature-usage/ios/FeatureUsage/Sources/FeatureUsage"
        ),
        .testTarget(
            name: "FeatureUsageTests",
            dependencies: ["FeatureUsage"],
            path: "feature-usage/ios/FeatureUsage/Tests/FeatureUsageTests"
        ),
    ]
)
