// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "UserMemory",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
    ],
    products: [
        .library(name: "UserMemory", targets: ["UserMemory"]),
    ],
    targets: [
        .target(name: "UserMemory"),
        .testTarget(name: "UserMemoryTests", dependencies: ["UserMemory"]),
    ]
)
