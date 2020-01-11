/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static com.android.server.wifi.WifiHealthMonitor.REASON_ASSOC_REJECTION;
import static com.android.server.wifi.WifiHealthMonitor.REASON_ASSOC_TIMEOUT;
import static com.android.server.wifi.WifiHealthMonitor.REASON_AUTH_FAILURE;
import static com.android.server.wifi.WifiHealthMonitor.REASON_CONNECTION_FAILURE;
import static com.android.server.wifi.WifiHealthMonitor.REASON_DISCONNECTION_NONLOCAL;
import static com.android.server.wifi.WifiHealthMonitor.REASON_SHORT_CONNECTION_NONLOCAL;
import static com.android.server.wifi.WifiScoreCard.CNT_ASSOCIATION_REJECTION;
import static com.android.server.wifi.WifiScoreCard.CNT_ASSOCIATION_TIMEOUT;
import static com.android.server.wifi.WifiScoreCard.CNT_AUTHENTICATION_FAILURE;
import static com.android.server.wifi.WifiScoreCard.CNT_CONNECTION_ATTEMPT;
import static com.android.server.wifi.WifiScoreCard.CNT_CONNECTION_DURATION_SEC;
import static com.android.server.wifi.WifiScoreCard.CNT_CONNECTION_FAILURE;
import static com.android.server.wifi.WifiScoreCard.CNT_DISCONNECTION_NONLOCAL;
import static com.android.server.wifi.WifiScoreCard.CNT_SHORT_CONNECTION_NONLOCAL;
import static com.android.server.wifi.WifiScoreCard.MIN_NUM_CONNECTION_ATTEMPT;
import static com.android.server.wifi.util.NativeUtil.hexStringFromByteArray;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiSsid;
import android.util.Base64;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiHealthMonitor.FailureStats;
import com.android.server.wifi.WifiScoreCard.NetworkConnectionStats;
import com.android.server.wifi.WifiScoreCard.PerNetwork;
import com.android.server.wifi.proto.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.proto.WifiScoreCardProto.ConnectionStats;
import com.android.server.wifi.proto.WifiScoreCardProto.Event;
import com.android.server.wifi.proto.WifiScoreCardProto.Network;
import com.android.server.wifi.proto.WifiScoreCardProto.NetworkList;
import com.android.server.wifi.proto.WifiScoreCardProto.NetworkStats;
import com.android.server.wifi.proto.WifiScoreCardProto.Signal;
import com.android.server.wifi.util.IntHistogram;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
/**
 * Unit tests for {@link com.android.server.wifi.WifiScoreCard}.
 */
@SmallTest
public class WifiScoreCardTest extends WifiBaseTest {
    static final WifiSsid TEST_SSID_1 = WifiSsid.createFromAsciiEncoded("Joe's Place");
    static final WifiSsid TEST_SSID_2 = WifiSsid.createFromAsciiEncoded("Poe's Ravn");

    static final MacAddress TEST_BSSID_1 = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
    static final MacAddress TEST_BSSID_2 = MacAddress.fromString("1:2:3:4:5:6");

    static final int TEST_NETWORK_AGENT_ID = 123;
    static final int TEST_NETWORK_CONFIG_ID = 1492;

    static final double TOL = 1e-6; // for assertEquals(double, double, tolerance)

    static final int TEST_BSSID_FAILURE_REASON =
            BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION;

    WifiScoreCard mWifiScoreCard;

    @Mock Clock mClock;
    @Mock WifiScoreCard.MemoryStore mMemoryStore;

    final ArrayList<String> mKeys = new ArrayList<>();
    final ArrayList<WifiScoreCard.BlobListener> mBlobListeners = new ArrayList<>();
    final ArrayList<byte[]> mBlobs = new ArrayList<>();

    long mMilliSecondsSinceBoot;
    ExtendedWifiInfo mWifiInfo;

    void millisecondsPass(long ms) {
        mMilliSecondsSinceBoot += ms;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(mMilliSecondsSinceBoot);
        when(mClock.getWallClockMillis()).thenReturn(mMilliSecondsSinceBoot + 1_500_000_000_000L);
    }

    void secondsPass(long s) {
        millisecondsPass(s * 1000);
    }

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mKeys.clear();
        mBlobListeners.clear();
        mBlobs.clear();
        mMilliSecondsSinceBoot = 0;
        mWifiInfo = new ExtendedWifiInfo(mock(Context.class));
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiInfo.setNetworkId(TEST_NETWORK_CONFIG_ID);
        millisecondsPass(0);
        mWifiScoreCard = new WifiScoreCard(mClock, "some seed");
        mWifiScoreCard.mPersistentHistograms = true; // TODO - remove when ready
    }

    /**
     * Test generic update
     */
    @Test
    public void testUpdate() throws Exception {
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());

        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        assertTrue(perBssid.id > 0);
        assertNotNull(perBssid.getL2Key());
        assertTrue("L2Key length should be more than 16.", perBssid.getL2Key().length() > 16);

        mWifiInfo.setBSSID(TEST_BSSID_2.toString());

        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        assertEquals(perBssid, mWifiScoreCard.fetchByBssid(TEST_BSSID_1));
        assertNotEquals(perBssid.id, mWifiScoreCard.fetchByBssid(TEST_BSSID_2).id);
        assertNotEquals(perBssid.getL2Key(), mWifiScoreCard.fetchByBssid(TEST_BSSID_2).getL2Key());
    }

    /**
     * Test the get, increment, and removal of Bssid blocklist streak counts.
     */
    @Test
    public void testBssidBlocklistStreakOperations() {
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        String ssid = mWifiInfo.getSSID();
        String bssid = mWifiInfo.getBSSID();
        assertEquals(0, mWifiScoreCard.getBssidBlocklistStreak(
                ssid, bssid, TEST_BSSID_FAILURE_REASON));
        for (int i = 1; i < 3; i++) {
            assertEquals(i, mWifiScoreCard.incrementBssidBlocklistStreak(
                    ssid, bssid, TEST_BSSID_FAILURE_REASON));
            assertEquals(i, mWifiScoreCard.getBssidBlocklistStreak(
                    ssid, bssid, TEST_BSSID_FAILURE_REASON));
        }
        mWifiScoreCard.resetBssidBlocklistStreak(ssid, bssid, TEST_BSSID_FAILURE_REASON);
        assertEquals(0, mWifiScoreCard.getBssidBlocklistStreak(
                ssid, bssid, TEST_BSSID_FAILURE_REASON));
    }

    /**
     * Test clearing the blocklist streak for all APs belonging to a SSID.
     */
    @Test
    public void testClearBssidBlocklistStreakForSsid() {
        // Increment and verify the blocklist streak for SSID_1, BSSID_1
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        for (int i = 1; i < 3; i++) {
            assertEquals(i, mWifiScoreCard.incrementBssidBlocklistStreak(
                    mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));
            assertEquals(i, mWifiScoreCard.getBssidBlocklistStreak(
                    mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));
        }

        // Increment and verify the blocklist streak for SSID_2, BSSID_2
        mWifiInfo.setSSID(TEST_SSID_2);
        mWifiInfo.setBSSID(TEST_BSSID_2.toString());
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        for (int i = 1; i < 3; i++) {
            assertEquals(i, mWifiScoreCard.incrementBssidBlocklistStreak(
                    mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));
            assertEquals(i, mWifiScoreCard.getBssidBlocklistStreak(
                    mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));
        }

        // Clear the blocklist streak for SSID_2
        mWifiScoreCard.resetBssidBlocklistStreakForSsid(mWifiInfo.getSSID());
        // Verify that the blocklist streak for SSID_2 is cleared.
        assertEquals(0, mWifiScoreCard.getBssidBlocklistStreak(
                mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));

        // verify that the blocklist streak for SSID_1 is not cleared.
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        assertEquals(2, mWifiScoreCard.getBssidBlocklistStreak(
                mWifiInfo.getSSID(), mWifiInfo.getBSSID(), TEST_BSSID_FAILURE_REASON));
    }

    /**
     * Test the update and retrieval of the last connection time to a BSSID.
     */
    @Test
    public void testSetBssidConnectionTimestampMs() {
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);

        String ssid = mWifiInfo.getSSID();
        String bssid = mWifiInfo.getBSSID();
        assertEquals(0L, mWifiScoreCard.getBssidConnectionTimestampMs(ssid, bssid));
        assertEquals(0L, mWifiScoreCard.setBssidConnectionTimestampMs(ssid, bssid, 100L));
        assertEquals(100L, mWifiScoreCard.getBssidConnectionTimestampMs(ssid, bssid));
    }

    /**
     * Test identifiers.
     */
    @Test
    public void testIdentifiers() throws Exception {
        mWifiInfo.setSSID(TEST_SSID_1);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        Pair<String, String> p1 = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
        assertNotNull(p1.first);
        assertNotNull(p1.second);
        mWifiInfo.setBSSID(TEST_BSSID_2.toString());
        Pair<String, String> p2 = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
        assertNotEquals(p1.first, p2.first);
        assertEquals(p1.second, p2.second);
        mWifiInfo.setBSSID(null);
        Pair<String, String> p3 = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
        assertNull(p3.first);
        assertNull(p3.second);
        mWifiInfo.setBSSID("02:00:00:00:00:00");
        Pair<String, String> p4 = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
        assertNull(p4.first);
        assertNull(p4.second);
    }

    /**
     * Test rssi poll updates
     */
    @Test
    public void testRssiPollUpdates() throws Exception {
        // Start out on one frequency
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(-77);
        mWifiInfo.setLinkSpeed(12);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        // Switch channels for a bit
        mWifiInfo.setFrequency(5290);
        mWifiInfo.setRssi(-66);
        mWifiInfo.setLinkSpeed(666);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        // Back to the first channel
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(-55);
        mWifiInfo.setLinkSpeed(86);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);

        double expectSum = -77 + -55;
        double expectSumSq = 77 * 77 + 55 * 55;

        // Now verify
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        // Looking up the same thing twice should yield the same object.
        assertTrue(perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                == perBssid.lookupSignal(Event.SIGNAL_POLL, 5805));
        // Check the rssi statistics for the first channel
        assertEquals(2, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).rssi.count);
        assertEquals(expectSum, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sum, TOL);
        assertEquals(expectSumSq, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.sumOfSquares, TOL);
        assertEquals(-77.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.minValue, TOL);
        assertEquals(-55.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.maxValue, TOL);
        // Check the rssi statistics for the second channel
        assertEquals(1, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).rssi.count);
        // Check that the linkspeed was updated
        assertEquals(666.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5290).linkspeed.sum, TOL);
    }

    /**
     * Statistics on time-to-connect, connection duration
     */
    @Test
    public void testDurationStatistics() throws Exception {
        // Start out disconnected; start connecting
        mWifiInfo.setBSSID(android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS);
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        // First poll has a bad RSSI
        millisecondsPass(111);
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        // A bit later, connection is complete (up through DHCP)
        millisecondsPass(222);
        mWifiInfo.setRssi(-55);
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        millisecondsPass(666);
        // Rssi polls for 99 seconds
        for (int i = 0; i < 99; i += 3) {
            mWifiScoreCard.noteSignalPoll(mWifiInfo);
            secondsPass(3);
        }
        // Make sure our simulated time adds up
        assertEquals(mMilliSecondsSinceBoot, 99999);
        // Validation success, rather late!
        mWifiScoreCard.noteValidationSuccess(mWifiInfo);
        // A long while later, wifi is toggled off
        secondsPass(9900);
        // Second validation success should not matter.
        mWifiScoreCard.noteValidationSuccess(mWifiInfo);
        mWifiScoreCard.noteWifiDisabled(mWifiInfo);


        // Now verify
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        assertEquals(1, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.count);
        assertEquals(333.0, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.sum, TOL);
        assertEquals(9999999.0, perBssid.lookupSignal(Event.WIFI_DISABLED, 5805)
                .elapsedMs.maxValue, TOL);
        assertEquals(999.0,  perBssid.lookupSignal(Event.FIRST_POLL_AFTER_CONNECTION, 5805)
                .elapsedMs.minValue, TOL);
        assertEquals(99999.0, perBssid.lookupSignal(Event.VALIDATION_SUCCESS, 5805)
                .elapsedMs.sum, TOL);
        assertNull(perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).elapsedMs);
    }

    /**
     * Constructs a protobuf form of AccessPoint example.
     */
    private byte[] makeSerializedAccessPointExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(10);
        // Association completes, a NetworkAgent is created
        mWifiScoreCard.noteNetworkAgentCreated(mWifiInfo, TEST_NETWORK_AGENT_ID);
        millisecondsPass(101);
        mWifiInfo.setRssi(-55);
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setLinkSpeed(384);
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        millisecondsPass(888);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(1000);
        mWifiInfo.setRssi(-44);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        mWifiInfo.setFrequency(2432);
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < HISTOGRAM_COUNT.length; i++) {
                if (HISTOGRAM_COUNT[i] > round) {
                    mWifiInfo.setRssi(HISTOGRAM_RSSI[i]);
                    mWifiScoreCard.noteSignalPoll(mWifiInfo);
                }
            }
        }
        mWifiScoreCard.resetConnectionState();

        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        perBssid.lookupSignal(Event.SIGNAL_POLL, 2412).rssi.historicalMean = -42.0;
        perBssid.lookupSignal(Event.SIGNAL_POLL, 2412).rssi.historicalVariance = 4.0;
        checkSerializationBssidExample("before serialization", perBssid);
        // Now convert to protobuf form
        byte[] serialized = perBssid.toAccessPoint().toByteArray();
        return serialized;
    }
    private static final int[] HISTOGRAM_RSSI = {-80, -79, -78};
    private static final int[] HISTOGRAM_COUNT = {3, 1, 4};

    private void checkHistogramExample(String diag, IntHistogram rssiHistogram) {
        int i = 0;
        for (IntHistogram.Bucket bucket : rssiHistogram) {
            if (bucket.count != 0) {
                assertTrue(diag, i < HISTOGRAM_COUNT.length);
                assertEquals(diag, HISTOGRAM_RSSI[i], bucket.start);
                assertEquals(diag, HISTOGRAM_COUNT[i], bucket.count);
                i++;
            }
        }
        assertEquals(diag, HISTOGRAM_COUNT.length, i);
    }

    /**
     * Checks that the fields of the bssid serialization example are as expected
     */
    private void checkSerializationBssidExample(String diag, WifiScoreCard.PerBssid perBssid) {
        assertEquals(diag, 2, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805).rssi.count);
        assertEquals(diag, -55.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.minValue, TOL);
        assertEquals(diag, -44.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 5805)
                .rssi.maxValue, TOL);
        assertEquals(diag, 384.0, perBssid.lookupSignal(Event.FIRST_POLL_AFTER_CONNECTION, 5805)
                .linkspeed.sum, TOL);
        assertEquals(diag, 111.0, perBssid.lookupSignal(Event.IP_CONFIGURATION_SUCCESS, 5805)
                .elapsedMs.minValue, TOL);
        assertEquals(diag, 0, perBssid.lookupSignal(Event.SIGNAL_POLL, 2412).rssi.count);
        assertEquals(diag, -42.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 2412)
                .rssi.historicalMean, TOL);
        assertEquals(diag, 4.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 2412)
                .rssi.historicalVariance, TOL);
        checkHistogramExample(diag, perBssid.lookupSignal(Event.SIGNAL_POLL,
                2432).rssi.intHistogram);
    }

    /**
     * Checks that the fields of the network are as expected with bssid serialization example
     */
    private void checkSerializationBssidExample(String diag, PerNetwork perNetwork) {
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();
        assertEquals(diag, 1, dailyStats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(diag, 0, dailyStats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(diag, 1, dailyStats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(diag, 0, dailyStats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
        assertEquals(diag, 0, dailyStats.getCount(CNT_DISCONNECTION_NONLOCAL));
        assertEquals(diag, 0, dailyStats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(diag, 0, dailyStats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(diag, 0, dailyStats.getCount(CNT_AUTHENTICATION_FAILURE));
    }

    /**
     * AccessPoint serialization
     */
    @Test
    public void testAccessPointSerialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();

        // Verify by parsing it and checking that we see the expected results
        AccessPoint ap = AccessPoint.parseFrom(serialized);
        assertEquals(5, ap.getEventStatsCount());
        for (Signal signal: ap.getEventStatsList()) {
            if (signal.getFrequency() == 2412) {
                assertFalse(signal.getRssi().hasCount());
                assertEquals(-42.0, signal.getRssi().getHistoricalMean(), TOL);
                assertEquals(4.0, signal.getRssi().getHistoricalVariance(), TOL);
                continue;
            }
            if (signal.getFrequency() == 2432) {
                assertEquals(Event.SIGNAL_POLL, signal.getEvent());
                assertEquals(HISTOGRAM_RSSI[2], signal.getRssi().getBuckets(2).getLow());
                assertEquals(HISTOGRAM_COUNT[2], signal.getRssi().getBuckets(2).getNumber());
                continue;
            }
            assertEquals(5805, signal.getFrequency());
            switch (signal.getEvent()) {
                case IP_CONFIGURATION_SUCCESS:
                    assertEquals(384.0, signal.getLinkspeed().getMaxValue(), TOL);
                    assertEquals(111.0, signal.getElapsedMs().getMinValue(), TOL);
                    break;
                case SIGNAL_POLL:
                    assertEquals(2, signal.getRssi().getCount());
                    break;
                case FIRST_POLL_AFTER_CONNECTION:
                    assertEquals(-55.0, signal.getRssi().getSum(), TOL);
                    break;
                default:
                    fail(signal.getEvent().toString());
            }
        }
    }

    /**
     * Serialization should be reproducible
     */
    @Test
    public void testReproducableSerialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();
        setUp();
        assertArrayEquals(serialized, makeSerializedAccessPointExample());
    }

    /**
     * AccessPoint Deserialization
     */
    @Test
    public void testAccessPointDeserialization() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();
        setUp(); // Get back to the initial state

        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.perBssidFromAccessPoint(
                mWifiInfo.getSSID(),
                AccessPoint.parseFrom(serialized));

        // Now verify
        String diag = hexStringFromByteArray(serialized);
        checkSerializationBssidExample(diag, perBssid);
    }

    /**
     * Serialization of all internally represented networks
     */
    @Test
    public void testNetworksSerialization() throws Exception {
        makeSerializedAccessPointExample();

        byte[] serialized = mWifiScoreCard.getNetworkListByteArray(false);
        byte[] cleaned = mWifiScoreCard.getNetworkListByteArray(true);
        String base64Encoded = mWifiScoreCard.getNetworkListBase64(true);

        setUp(); // Get back to the initial state
        String diag = hexStringFromByteArray(serialized);
        NetworkList networkList = NetworkList.parseFrom(serialized);
        assertEquals(diag, 1, networkList.getNetworksCount());
        Network network = networkList.getNetworks(0);
        assertEquals(diag, 1, network.getAccessPointsCount());
        AccessPoint accessPoint = network.getAccessPoints(0);
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.perBssidFromAccessPoint(network.getSsid(),
                accessPoint);
        NetworkStats networkStats = network.getNetworkStats();
        PerNetwork perNetwork = mWifiScoreCard.perNetworkFromNetworkStats(network.getSsid(),
                networkStats);

        checkSerializationBssidExample(diag, perBssid);
        checkSerializationBssidExample(diag, perNetwork);
        // Leaving out the bssids should make the cleaned version shorter.
        assertTrue(cleaned.length < serialized.length);
        // Check the Base64 version
        assertTrue(Arrays.equals(cleaned, Base64.decode(base64Encoded, Base64.DEFAULT)));
        // Check that the network ids were carried over
        assertEquals(TEST_NETWORK_AGENT_ID, network.getNetworkAgentId());
        assertEquals(TEST_NETWORK_CONFIG_ID, network.getNetworkConfigId());
    }

    /**
     * Installation of memory store does not crash
     */
    @Test
    public void testInstallationOfMemoryStoreDoesNotCrash() throws Exception {
        mWifiScoreCard.installMemoryStore(mMemoryStore);
        makeSerializedAccessPointExample();
        mWifiScoreCard.installMemoryStore(mMemoryStore);
    }

    /**
     * Merge of lazy reads
     */
    @Test
    public void testLazyReads() {
        // Install our own MemoryStore object, which records read requests
        mWifiScoreCard.installMemoryStore(new WifiScoreCard.MemoryStore() {
            @Override
            public void read(String key, String name, WifiScoreCard.BlobListener listener) {
                mKeys.add(key);
                mBlobListeners.add(listener);
            }
            @Override
            public void write(String key, String name, byte[] value) {
                // ignore for now
            }
        });

        // Now make some changes
        byte[] serialized = makeSerializedAccessPointExample();
        // 1 for perfBssid and 1 for perNetwork
        assertEquals(2, mKeys.size());

        // Simulate the asynchronous completion of the read request
        millisecondsPass(33);
        mBlobListeners.get(0).onBlobRetrieved(serialized);

        // Check that the historical mean and variance were updated accordingly
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.fetchByBssid(TEST_BSSID_1);
        assertEquals(-42.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 2412)
                .rssi.historicalMean, TOL);
        assertEquals(4.0, perBssid.lookupSignal(Event.SIGNAL_POLL, 2412)
                .rssi.historicalVariance, TOL);
    }

    /**
     * Write test
     */
    @Test
    public void testWrites() throws Exception {
        // Install our own MemoryStore object, which records write requests
        mWifiScoreCard.installMemoryStore(new WifiScoreCard.MemoryStore() {
            @Override
            public void read(String key, String name, WifiScoreCard.BlobListener listener) {
                // Just record these, never answer
                mBlobListeners.add(listener);
            }
            @Override
            public void write(String key, String name, byte[] value) {
                mKeys.add(key);
                mBlobs.add(value);
            }
        });

        // Make some changes
        byte[] serialized = makeSerializedAccessPointExample();
        // 1 for perfBssid and 1 for perNetwork
        assertEquals(2, mBlobListeners.size());

        secondsPass(33);

        // There should be one changed bssid now. We may have already done some writes.
        mWifiScoreCard.doWrites();
        assertTrue(mKeys.size() > 0);

        // The written blob should not contain the BSSID, though the full serialized version does
        String writtenHex = hexStringFromByteArray(mBlobs.get(mKeys.size() - 1));
        String fullHex = hexStringFromByteArray(serialized);
        String bssidHex = hexStringFromByteArray(TEST_BSSID_1.toByteArray());
        assertFalse(writtenHex, writtenHex.contains(bssidHex));
        assertTrue(fullHex, fullHex.contains(bssidHex));

        // A second write request should not find anything to write
        final int beforeSize = mKeys.size();
        assertEquals(0, mWifiScoreCard.doWrites());
        assertEquals(beforeSize, mKeys.size());
    }

    /**
     * Calling doWrites before installing a MemoryStore should do nothing.
     */
    @Test
    public void testNoWritesUntilReady() throws Exception {
        makeSerializedAccessPointExample();
        assertEquals(0, mWifiScoreCard.doWrites());
    }

    /**
     * Installing a MemoryStore after startup should issue reads.
     */
    @Test
    public void testReadAfterDelayedMemoryStoreInstallation() throws Exception {
        makeSerializedAccessPointExample();
        mWifiScoreCard.installMemoryStore(mMemoryStore);
        // 1 for requestReadBssid
        verify(mMemoryStore, times(1)).read(any(), any(), any());
    }

    /**
     * Calling clear should forget the state.
     */
    @Test
    public void testClearReallyDoesClearTheState() throws Exception {
        byte[] serialized = makeSerializedAccessPointExample();
        assertNotEquals(0, serialized.length);
        mWifiScoreCard.clear();
        byte[] leftovers = mWifiScoreCard.getNetworkListByteArray(false);
        assertEquals(0, leftovers.length);
    }

    /**
     * Test that older items are evicted from memory.
     */
    @Test
    public void testOlderItemsShouldBeEvicted() throws Exception {
        mWifiInfo.setRssi(-55);
        mWifiInfo.setFrequency(5805);
        mWifiInfo.setLinkSpeed(384);
        mWifiScoreCard.installMemoryStore(mMemoryStore);
        for (int i = 0; i < 256; i++) {
            MacAddress bssid = MacAddress.fromBytes(new byte[]{2, 2, 2, 2, 2, (byte) i});
            mWifiInfo.setBSSID(bssid.toString());
            mWifiScoreCard.noteSignalPoll(mWifiInfo);
        }
        // 256 for requestReadBssid() and 1 for requestReadNetwork()
        verify(mMemoryStore, times(256 + 1)).read(any(), any(), any());
        verify(mMemoryStore, atLeastOnce()).write(any(), any(), any()); // Assumes target size < 256
        reset(mMemoryStore);

        for (int i = 256 - 3; i < 256; i++) {
            MacAddress bssid = MacAddress.fromBytes(new byte[]{2, 2, 2, 2, 2, (byte) i});
            mWifiInfo.setBSSID(bssid.toString());
            mWifiScoreCard.noteSignalPoll(mWifiInfo);
        }
        verify(mMemoryStore, never()).read(any(), any(), any()); // Assumes target size >= 3

        for (int i = 0; i < 3; i++) {
            MacAddress bssid = MacAddress.fromBytes(new byte[]{2, 2, 2, 2, 2, (byte) i});
            mWifiInfo.setBSSID(bssid.toString());
            mWifiScoreCard.noteSignalPoll(mWifiInfo);
        }
        verify(mMemoryStore, times(3)).read(any(), any(), any()); // Assumes target size < 253
    }

    private void makeAssocTimeOutExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -53, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT);
    }

    /**
     * Check network stats after association timeout.
     */
    @Test
    public void testNetworkAssocTimeOut() throws Exception {
        makeAssocTimeOutExample();

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();

        assertEquals(1, dailyStats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(1, dailyStats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(0, dailyStats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(0, dailyStats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(1, dailyStats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(0, dailyStats.getCount(CNT_AUTHENTICATION_FAILURE));
    }

    private void makeAuthFailureAndWrongPassword() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(500);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -53, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_AUTHENTICATION_FAILURE);
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -53, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_WRONG_PASSWORD);
    }

    /**
     * Check network stats after authentication failure and wrong password.
     */
    @Test
    public void testNetworkAuthenticationFailureWrongPassword() throws Exception {
        makeAuthFailureAndWrongPassword();
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();

        assertEquals(2, dailyStats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(1, dailyStats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(0, dailyStats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(0, dailyStats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(0, dailyStats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(1, dailyStats.getCount(CNT_AUTHENTICATION_FAILURE));
    }

    /**
     * Check network stats when a new connection attempt for SSID2 is issued
     * before disconnection of SSID1
     */
    @Test
    public void testNetworkSwitchWithOverlapping() throws Exception {
        // Connect to SSID_1
        String ssid1 = mWifiInfo.getSSID();
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, ssid1);
        millisecondsPass(5000);

        // Attempt to connect to SSID_2
        mWifiInfo.setSSID(TEST_SSID_2);
        mWifiInfo.setBSSID(TEST_BSSID_2.toString());
        String ssid2 = mWifiInfo.getSSID();
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, ssid2);

        // Disconnect from SSID_1
        millisecondsPass(100);
        int disconnectionReason = 3;
        mWifiScoreCard.noteNonlocalDisconnect(disconnectionReason);
        millisecondsPass(100);
        mWifiScoreCard.resetConnectionState();

        // SSID_2 is connected and then disconnected
        millisecondsPass(2000);
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
        millisecondsPass(2000);
        mWifiScoreCard.resetConnectionState();

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(ssid1);
        assertEquals(5, perNetwork.getRecentStats().getCount(CNT_CONNECTION_DURATION_SEC));

        perNetwork = mWifiScoreCard.fetchByNetwork(ssid2);
        assertEquals(4, perNetwork.getRecentStats().getCount(CNT_CONNECTION_DURATION_SEC));
    }

    /**
     * Check network stats after 2 connection failures at low RSSI.
     */
    @Test
    public void testNetworkConnectionFailureLowRssi() throws Exception {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -83, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -83, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION);
        millisecondsPass(3000);
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -83, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -83, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION);

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();
        checkShortConnectionExample(dailyStats, 0);
    }

    private void makeShortConnectionExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(5000);
        mWifiInfo.setTxLinkSpeedMbps(100);
        mWifiInfo.setTxSuccessRate(20.0);
        mWifiInfo.setTxRetriesRate(1.0);
        mWifiInfo.setRssi(-80);
        millisecondsPass(1000);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(2000);
        int disconnectionReason = 3;
        mWifiScoreCard.noteNonlocalDisconnect(disconnectionReason);
        millisecondsPass(1000);
        mWifiScoreCard.resetConnectionState();
    }

    private void checkShortConnectionExample(NetworkConnectionStats stats, int scale) {
        assertEquals(1 * scale, stats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(0, stats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(9 * scale, stats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(0, stats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(0, stats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(0, stats.getCount(CNT_AUTHENTICATION_FAILURE));
        assertEquals(1 * scale, stats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
        assertEquals(1 * scale, stats.getCount(CNT_DISCONNECTION_NONLOCAL));
    }

    private void makeShortConnectionOldRssiPollingExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(2000);
        mWifiInfo.setRssi(-55);
        millisecondsPass(1000);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(29000);
        int disconnectionReason = 3;
        mWifiScoreCard.noteNonlocalDisconnect(disconnectionReason);
        millisecondsPass(1000);
        mWifiScoreCard.resetConnectionState();
    }

    private void checkShortConnectionOldPollingExample(NetworkConnectionStats stats) {
        assertEquals(1, stats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(0, stats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(33, stats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(0, stats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(0, stats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(0, stats.getCount(CNT_AUTHENTICATION_FAILURE));
        assertEquals(0, stats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
        assertEquals(0, stats.getCount(CNT_DISCONNECTION_NONLOCAL));
    }

    /**
     * Check network stats after RSSI poll and disconnection.
     */
    @Test
    public void testNetworkRssiPollShortNonlocalDisconnection() throws Exception {
        // 1st connection session
        makeShortConnectionExample();
        // 2nd connection session
        makeNormalConnectionExample();

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();
        assertEquals(2, dailyStats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(0, dailyStats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(20, dailyStats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(0, dailyStats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(0, dailyStats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(0, dailyStats.getCount(CNT_AUTHENTICATION_FAILURE));
        assertEquals(1, dailyStats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
        assertEquals(1, dailyStats.getCount(CNT_DISCONNECTION_NONLOCAL));
    }

    /**
     * Check network stats after short connection with an old RSSI polling
     */
    @Test
    public void testShortNonlocalDisconnectionOldRssiPolling() throws Exception {
        makeShortConnectionOldRssiPollingExample();
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        checkShortConnectionOldPollingExample(perNetwork.getRecentStats());
    }

    private void makeNormalConnectionExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiInfo.setRssi(-55);
        millisecondsPass(7000);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(3000);
        mWifiScoreCard.resetConnectionState();
    }

    /**
     * Constructs a protobuf form of Network example.
     */
    private byte[] makeSerializedNetworkExample() {
        makeNormalConnectionExample();

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        checkSerializationNetworkExample("before serialization", perNetwork);
        // Now convert to protobuf form
        byte[] serialized = perNetwork.toNetworkStats().toByteArray();
        return serialized;
    }

    /**
     * Checks that the fields of the network serialization example are as expected.
     */
    private void checkSerializationNetworkExample(String diag, PerNetwork perNetwork) {
        NetworkConnectionStats dailyStats = perNetwork.getRecentStats();
        assertEquals(diag, 1, dailyStats.getCount(CNT_CONNECTION_ATTEMPT));
        assertEquals(diag, 0, dailyStats.getCount(CNT_CONNECTION_FAILURE));
        assertEquals(diag, 11, dailyStats.getCount(CNT_CONNECTION_DURATION_SEC));
        assertEquals(diag, 0, dailyStats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
        assertEquals(diag, 0, dailyStats.getCount(CNT_DISCONNECTION_NONLOCAL));
        assertEquals(diag, 0, dailyStats.getCount(CNT_ASSOCIATION_REJECTION));
        assertEquals(diag, 0, dailyStats.getCount(CNT_ASSOCIATION_TIMEOUT));
        assertEquals(diag, 0, dailyStats.getCount(CNT_AUTHENTICATION_FAILURE));
    }

    /**
     * Test NetworkStats serialization.
     */
    @Test
    public void testNetworkStatsSerialization() throws Exception {
        byte[] serialized = makeSerializedNetworkExample();

        // Verify by parsing it and checking that we see the expected results
        NetworkStats ns = NetworkStats.parseFrom(serialized);
        ConnectionStats dailyStats = ns.getRecentStats();
        assertEquals(1, dailyStats.getNumConnectionAttempt());
        assertEquals(0, dailyStats.getNumConnectionFailure());
        assertEquals(11, dailyStats.getConnectionDurationSec());
        assertEquals(0, dailyStats.getNumDisconnectionNonlocal());
        assertEquals(0, dailyStats.getNumShortConnectionNonlocal());
        assertEquals(0, dailyStats.getNumAssociationRejection());
        assertEquals(0, dailyStats.getNumAssociationTimeout());
        assertEquals(0, dailyStats.getNumAuthenticationFailure());
    }

    /**
     * Test NetworkStats Deserialization.
     */
    @Test
    public void testNetworkStatsDeserialization() throws Exception {
        byte[] serialized = makeSerializedNetworkExample();
        setUp(); // Get back to the initial state

        PerNetwork perNetwork = mWifiScoreCard.perNetworkFromNetworkStats(mWifiInfo.getSSID(),
                NetworkStats.parseFrom(serialized));

        // Now verify
        String diag = hexStringFromByteArray(serialized);
        checkSerializationNetworkExample(diag, perNetwork);
    }

    /**
     * Check network stats after network connection and then removeNetWork().
     */
    @Test
    public void testRemoveNetwork() throws Exception {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(1000);
        mWifiScoreCard.noteConnectionFailure(mWifiInfo, -53, mWifiInfo.getSSID(),
                BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION);
        mWifiScoreCard.removeNetwork(mWifiInfo.getSSID());

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertNull(perNetwork);
    }

    @Test
    public void testUpdateAfterDailyDetection() throws Exception {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeShortConnectionExample();
        }

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        perNetwork.updateAfterDailyDetection();

        checkShortConnectionExample(perNetwork.getRecentStats(), 0);
        checkShortConnectionExample(perNetwork.getStatsCurrBuild(), MIN_NUM_CONNECTION_ATTEMPT);
        checkShortConnectionExample(perNetwork.getStatsPrevBuild(), 0);
    }

    @Test
    public void testUpdateAfterSwBuildChange() throws Exception {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeShortConnectionExample();
        }
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        perNetwork.updateAfterDailyDetection();
        perNetwork.updateAfterSwBuildChange();

        checkShortConnectionExample(perNetwork.getRecentStats(), 0);
        checkShortConnectionExample(perNetwork.getStatsCurrBuild(), 0);
        checkShortConnectionExample(perNetwork.getStatsPrevBuild(), MIN_NUM_CONNECTION_ATTEMPT);
    }

    private void makeRecentStatsWithGoodConnection() {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeNormalConnectionExample();
        }
    }

    private void makeRecentStatsWithShortConnection() {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeShortConnectionExample();
        }
    }

    private void makeRecentStatsWithAssocTimeOut() {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeAssocTimeOutExample();
        }
    }

    private void makeRecentStatsWithAuthFailure() {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeAuthFailureAndWrongPassword();
        }
    }

    private void checkStatsDeltaExample(FailureStats stats, int scale) {
        assertEquals(0, stats.getCount(REASON_ASSOC_REJECTION));
        assertEquals(1 * scale, stats.getCount(REASON_ASSOC_TIMEOUT));
        assertEquals(1 * scale, stats.getCount(REASON_AUTH_FAILURE));
        assertEquals(1 * scale, stats.getCount(REASON_CONNECTION_FAILURE));
        assertEquals(1 * scale, stats.getCount(REASON_DISCONNECTION_NONLOCAL));
        assertEquals(1 * scale, stats.getCount(REASON_SHORT_CONNECTION_NONLOCAL));
    }

    /**
     * Check if daily detection is skipped with insufficient daily stats.
     */
    @Test
    public void testDailyDetectionWithInsufficientRecentStats() throws Exception {
        PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(mWifiInfo.getSSID());
        makeShortConnectionExample();

        FailureStats statsDec = new FailureStats();
        FailureStats statsInc = new FailureStats();
        FailureStats statsHigh = new FailureStats();
        int detectionFlag = perNetwork.dailyDetection(statsDec, statsInc, statsHigh);
        assertEquals(WifiScoreCard.INSUFFICIENT_RECENT_STATS, detectionFlag);
        checkStatsDeltaExample(statsDec, 0);
        checkStatsDeltaExample(statsInc, 0);
        checkStatsDeltaExample(statsHigh, 0);
        perNetwork.updateAfterDailyDetection();
        checkShortConnectionExample(perNetwork.getRecentStats(), 1);
        checkShortConnectionExample(perNetwork.getStatsPrevBuild(), 0);
    }

    /**
     * Run a few days with good connection, followed by a SW build change which results
     * in performance regression. Check if the regression is detected properly.
     */
    @Test
    public void testRegressionAfterSwBuildChange() throws Exception {
        PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(mWifiInfo.getSSID());
        int numGoodConnectionDays = 5;
        for (int i = 0; i < numGoodConnectionDays; i++) {
            makeRecentStatsWithGoodConnection();
            perNetwork.updateAfterDailyDetection();
        }

        perNetwork.updateAfterSwBuildChange();
        makeRecentStatsWithShortConnection();
        makeRecentStatsWithAssocTimeOut();
        makeRecentStatsWithAuthFailure();

        FailureStats statsDec = new FailureStats();
        FailureStats statsInc = new FailureStats();
        FailureStats statsHigh = new FailureStats();
        int detectionFlag = perNetwork.dailyDetection(statsDec, statsInc, statsHigh);
        assertEquals(WifiScoreCard.SUFFICIENT_RECENT_PREV_STATS, detectionFlag);
        checkStatsDeltaExample(statsDec, 0);
        checkStatsDeltaExample(statsInc, 1);
        checkStatsDeltaExample(statsHigh, 0);
    }

    /**
     * Run a few days with bad connections, followed by a SW build change which results
     * in performance improvement. Check if the improvement is detected properly.
     */
    @Test
    public void testImprovementAfterSwBuildChange() throws Exception {
        PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(mWifiInfo.getSSID());
        makeRecentStatsWithGoodConnection(); // Day 1
        perNetwork.updateAfterDailyDetection();
        makeRecentStatsWithAssocTimeOut();   // Day 2
        perNetwork.updateAfterDailyDetection();
        makeRecentStatsWithAuthFailure();    // Day 3
        perNetwork.updateAfterDailyDetection();
        makeRecentStatsWithShortConnection(); // Day 4
        perNetwork.updateAfterDailyDetection();
        makeRecentStatsWithShortConnection(); // Day 5
        perNetwork.updateAfterDailyDetection();

        perNetwork.updateAfterSwBuildChange();
        makeRecentStatsWithGoodConnection(); // Day 6

        FailureStats statsDec = new FailureStats();
        FailureStats statsInc = new FailureStats();
        FailureStats statsHigh = new FailureStats();
        perNetwork.dailyDetection(statsDec, statsInc, statsHigh);
        checkStatsDeltaExample(statsDec, 1);
        checkStatsDeltaExample(statsInc, 0);
        checkStatsDeltaExample(statsHigh, 0);
    }

    @Test
    public void testPoorConnectionWithoutHistory() throws Exception {
        PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(mWifiInfo.getSSID());

        makeRecentStatsWithShortConnection(); // Day 1
        makeRecentStatsWithAssocTimeOut();
        makeRecentStatsWithAuthFailure();

        FailureStats statsDec = new FailureStats();
        FailureStats statsInc = new FailureStats();
        FailureStats statsHigh = new FailureStats();
        int detectionFlag = perNetwork.dailyDetection(statsDec, statsInc, statsHigh);
        assertEquals(WifiScoreCard.SUFFICIENT_RECENT_STATS_ONLY, detectionFlag);
        checkStatsDeltaExample(statsDec, 0);
        checkStatsDeltaExample(statsInc, 0);
        checkStatsDeltaExample(statsHigh, 1);
    }
}
