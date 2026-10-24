package com.dripps.voxyserver;

/**
 * Main entry point for the VoxyServer security, bandwidth, distance pacing, and region hash
 * regression test suite on branch 26.3.
 */
public class TestRunner {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println(" VoxyServer Security & Bandwidth Test Suite (26.3)");
        System.out.println(
                " JDK: "
                        + System.getProperty("java.version")
                        + " ("
                        + System.getProperty("java.vendor")
                        + ")");
        System.out.println(
                " OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("=================================================\n");

        int passedSuites = 0;
        int totalSuites = 24;

        try {
            RuntimeLifecycleTest.runAllTests();
            passedSuites++;
            VersionSyncTest.runAllTests();
            passedSuites++;
            VersionLoadTest.runAllTests();
            passedSuites++;
            SourceColumnRefreshTest.runAllTests();
            passedSuites++;
            com.dripps.voxyserver.server.DeliveryProgressTest.runAllTests();
            passedSuites++;
            CommandPermissionTest.runAllTests();
            passedSuites++;
            ConfigResetTest.runAllTests();
            passedSuites++;
            DemandProtocolTest.runAllTests();
            passedSuites++;
            DemandStateTest.runAllTests();
            passedSuites++;
            DirtyMaintenanceTest.runAllTests();
            passedSuites++;
            SurfaceMaintenanceTest.runAllTests();
            passedSuites++;
            MaintenanceWakeupTest.runAllTests();
            passedSuites++;
            com.dripps.voxyserver.server.DemandIndexTest.runAllTests();
            passedSuites++;
            com.dripps.voxyserver.server.DemandLoadTest.runAllTests();
            passedSuites++;
            ManifestSnapshotTest.runAllTests();
            passedSuites++;
            OutboundQueueTest.runAllTests();
            passedSuites++;
            ConfigValidationTest.runAllTests();
            passedSuites++;

            NetworkSecurityTest.runAllTests();
            passedSuites++;

            BandwidthThrottlerTest.runAllTests();
            passedSuites++;

            DistancePacingTest.runAllTests();
            passedSuites++;

            SynchronizationProtocolTest.runAllTests();
            passedSuites++;

            SemanticSyncTest.runAllTests();
            passedSuites++;

            SemanticRegistryTest.runAllTests();
            passedSuites++;

            RegionHashTest.runAllTests();
            passedSuites++;

            System.out.println("=================================================");
            System.out.println(
                    " ALL "
                            + passedSuites
                            + "/"
                            + totalSuites
                            + " TEST SUITES PASSED VERIFICATION!");
            System.out.println("=================================================");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("\n[FAIL] Test suite failed with exception:");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
