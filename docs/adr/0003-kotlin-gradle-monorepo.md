# Use a Kotlin Gradle monorepo

The Android app, Ktor ingestion API, and their versioned transport contract live in one Gradle monorepo as independently bounded modules. A single language and coordinated change set reduce maintenance for this personal system, while the module boundaries prevent Samsung SDK types or persistence entities from becoming the network contract.
