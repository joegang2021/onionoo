/* Copyright 2011 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.util.*;
import org.torproject.descriptor.*;

/* Update search data and status data files. */
public class Main {
  public static void main(String[] args) {

    printStatus("Updating search data.");
    SearchDataWriter sedw = new SearchDataWriter();
    SearchData sd = sedw.readRelaySearchDataFile();
    sd.updateRelayNetworkConsensuses();
    sd.updateBridgeNetworkStatuses();
    sedw.writeRelaySearchDataFile(sd);

    printStatus("Updating status data.");
    StatusDataWriter stdw = new StatusDataWriter();
    stdw.setValidAfterMillis(sd.getLastValidAfterMillis());
    stdw.setFreshUntilMillis(sd.getLastFreshUntilMillis());
    stdw.setRelays(sd.getRelays());
    stdw.setBridges(sd.getBridges());
    stdw.updateRelayServerDescriptors();

    printStatus("Updating bandwidth data.");
    BandwidthDataWriter bdw = new BandwidthDataWriter();
    bdw.setRelays(sd.getRelays());
    bdw.setBridges(sd.getBridges());
    bdw.updateRelayExtraInfoDescriptors();
    bdw.deleteObsoleteBandwidthFiles();

    printStatus("Terminating.");
  }

  private static void printStatus(String message) {
    System.out.println(message);
  }
}

