import SwiftUI

@main
struct JasmineApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private struct ContentView: View {
    var body: some View {
        NavigationStack {
            List {
                Section("Library") {
                    Label("Manga", systemImage: "books.vertical")
                    Label("Updates", systemImage: "arrow.clockwise")
                    Label("History", systemImage: "clock")
                }
                Section {
                    Text("Jasmine iOS")
                        .font(.headline)
                    Text("The iOS shell is ready for the shared Kotlin domain and data layers.")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Jasmine")
        }
    }
}
