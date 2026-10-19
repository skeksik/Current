import Shared
import SwiftUI

// Placeholder until the first screen. The footnote reads a type from the Kotlin core, which proves the
// "Shared" framework (built by the :apple module) is linked on every Apple platform.
struct ContentView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("Current")
            Text("Kotlin core: \(String(describing: VideoRanges.self))")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}
