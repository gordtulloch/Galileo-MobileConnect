// swift-tools-version: 6.0
//
// GalileoCore - the hand-written, UI-free core of the iOS/iPadOS app: the `S-*` components of
// docs/SDD.md, on top of the generated GalileoAPI client. Must not import SwiftUI or UIKit, so that it
// stays testable from the command line with `swift test` (docs/ios/SDD.md, ADR-IOS-002).

import PackageDescription

let package = Package(
    name: "GalileoCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "GalileoCore", targets: ["GalileoCore"]),
    ],
    dependencies: [
        .package(path: "../GalileoAPI"),
        .package(url: "https://github.com/apple/swift-http-types", exact: "1.8.0"),
    ],
    targets: [
        .target(
            name: "GalileoCore",
            dependencies: [
                "GalileoAPI",
                .product(name: "HTTPTypes", package: "swift-http-types"),
            ],
            // Copied from api/scenarios by scripts/sync_api.py: S-MOCK is driven by the shared scenario data.
            resources: [.copy("Resources/scenarios")]
        ),
        .testTarget(
            name: "GalileoCoreTests",
            dependencies: [
                "GalileoCore", "GalileoAPI",
                .product(name: "HTTPTypes", package: "swift-http-types"),
            ]
        ),
    ]
)
