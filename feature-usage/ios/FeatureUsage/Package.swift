// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "FeatureUsage",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "FeatureUsage", targets: ["FeatureUsage"]),
    ],
    targets: [
        .target(name: "FeatureUsage"),
        .testTarget(name: "FeatureUsageTests", dependencies: ["FeatureUsage"]),
    ]
)
