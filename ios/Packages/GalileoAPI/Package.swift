// swift-tools-version: 5.9
//
// GalileoAPI - the request/response client for the Galileo wire API, GENERATED from api/openapi.yaml
// by Apple's swift-openapi-generator at build time (docs/SDD.md, ADR-APP-001). There is no generated
// source in the repository; edit api/openapi.yaml, run `python scripts/sync_api.py`, and build.
//
// Needs macOS with Xcode 15+ (the generator is a SwiftPM build-tool plugin).
// Versions are pinned exactly; Package.resolved pins their transitive dependencies. Move them on purpose.

import PackageDescription

let package = Package(
    name: "GalileoAPI",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "GalileoAPI", targets: ["GalileoAPI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-generator", exact: "1.14.4"),
        .package(url: "https://github.com/apple/swift-openapi-runtime", exact: "1.13.4"),
        .package(url: "https://github.com/apple/swift-openapi-urlsession", exact: "1.4.0"),
    ],
    targets: [
        .target(
            name: "GalileoAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
                .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
            ],
            plugins: [
                .plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator"),
            ]
        ),
    ]
)
