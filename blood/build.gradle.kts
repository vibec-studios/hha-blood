version = "0.9.0"

extensions.configure<BasePluginExtension> {
    archivesName.set("HHA_Blood")
}

// Real client test: starts the game, flies through the Slaughter Pit, plays the boss fight and takes
// screenshots (./gradlew :blood:runClientGameTest, pictures under blood/build/run/clientGameTest/screenshots).
extensions.configure<net.fabricmc.loom.api.fabricapi.FabricApiExtension> {
    configureTests {
        createSourceSet.set(true)
        modId.set("hha_blood-gametest")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
    }
}

// HHA 0.8.0 talks to the network below Fabric's packet API; the client-gametest network synchronizer
// cannot follow that and stops the test ("Detected interfacing with packets at a lower level").
// It only exists in the test environment - turn it off there.
extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI> {
    runs.matching { it.name == "clientGameTest" }.configureEach {
        vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
    }
}
