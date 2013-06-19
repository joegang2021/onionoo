/* Copyright 2011, 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.torproject.descriptor.BridgeNetworkStatus;
import org.torproject.descriptor.Descriptor;
import org.torproject.descriptor.NetworkStatusEntry;
import org.torproject.descriptor.RelayNetworkStatusConsensus;
import org.torproject.onionoo.LookupService.LookupResult;

/* Store relays and bridges that have been running in the past seven
 * days. */
public class CurrentNodes {

  private DescriptorSource descriptorSource;

  private LookupService lookupService;

  private DocumentStore documentStore;

  /* Initialize an instance for the back-end that is read-only and doesn't
   * support parsing new descriptor contents. */
  public CurrentNodes(DocumentStore documentStore) {
    this(null, null, documentStore);
  }

  public CurrentNodes(DescriptorSource descriptorSource,
      LookupService lookupService, DocumentStore documentStore) {
    this.descriptorSource = descriptorSource;
    this.lookupService = lookupService;
    this.documentStore = documentStore;
  }

  public void readStatusSummary() {
    String summaryString = this.documentStore.retrieve(
        DocumentType.STATUS_SUMMARY);
    this.initializeFromSummaryString(summaryString);
  }

  public void readOutSummary() {
    String summaryString = this.documentStore.retrieve(
        DocumentType.OUT_SUMMARY);
    this.initializeFromSummaryString(summaryString);
  }

  private void initializeFromSummaryString(String summaryString) {
    if (summaryString == null) {
      return;
    }
    Scanner s = new Scanner(summaryString);
    while (s.hasNextLine()) {
      String line = s.nextLine();
      this.parseSummaryFileLine(line);
    }
    s.close();
  }

  private void parseSummaryFileLine(String line) {
    boolean isRelay;
    String nickname, fingerprint, address, countryCode = "??",
        hostName = null, defaultPolicy = null, portList = null,
        aSNumber = null;
    SortedSet<String> orAddressesAndPorts, exitAddresses, relayFlags;
    long publishedOrValidAfterMillis, consensusWeight = -1L,
        lastRdnsLookup = -1L, firstSeenMillis, lastChangedAddresses;
    int orPort, dirPort;
    try {
      SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
          "yyyy-MM-dd HH:mm:ss");
      dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      String[] parts = line.split(" ");
      isRelay = parts[0].equals("r");
      if (parts.length < 9) {
        System.err.println("Too few space-separated values in line '"
            + line + "'.  Skipping.");
        return;
      }
      nickname = parts[1];
      fingerprint = parts[2];
      String addresses = parts[3];
      orAddressesAndPorts = new TreeSet<String>();
      exitAddresses = new TreeSet<String>();
      if (addresses.contains(";")) {
        String[] addressParts = addresses.split(";", -1);
        if (addressParts.length != 3) {
          System.err.println("Invalid addresses entry in line '" + line
              + "'.  Skipping.");
          return;
        }
        address = addressParts[0];
        if (addressParts[1].length() > 0) {
          orAddressesAndPorts.addAll(Arrays.asList(
              addressParts[1].split("\\+")));
        }
        if (addressParts[2].length() > 0) {
          exitAddresses.addAll(Arrays.asList(
              addressParts[2].split("\\+")));
        }
      } else {
        address = addresses;
      }
      publishedOrValidAfterMillis = dateTimeFormat.parse(
          parts[4] + " " + parts[5]).getTime();
      orPort = Integer.parseInt(parts[6]);
      dirPort = Integer.parseInt(parts[7]);
      relayFlags = new TreeSet<String>(
          Arrays.asList(parts[8].split(",")));
      if (parts.length > 9) {
        consensusWeight = Long.parseLong(parts[9]);
      }
      if (parts.length > 10) {
        countryCode = parts[10];
      }
      if (parts.length > 12) {
        hostName = parts[11].equals("null") ? null : parts[11];
        lastRdnsLookup = Long.parseLong(parts[12]);
      }
      if (parts.length > 14) {
        if (!parts[13].equals("null")) {
          defaultPolicy = parts[13];
        }
        if (!parts[14].equals("null")) {
          portList = parts[14];
        }
      }
      firstSeenMillis = publishedOrValidAfterMillis;
      if (parts.length > 16) {
        firstSeenMillis = dateTimeFormat.parse(parts[15] + " "
            + parts[16]).getTime();
      }
      lastChangedAddresses = publishedOrValidAfterMillis;
      if (parts.length > 18 && !parts[17].equals("null")) {
        lastChangedAddresses = dateTimeFormat.parse(parts[17] + " "
            + parts[18]).getTime();
      }
      if (parts.length > 19) {
        aSNumber = parts[19];
      }
    } catch (NumberFormatException e) {
      System.err.println("Number format exception while parsing line '"
          + line + "': " + e.getMessage() + ".  Skipping.");
      return;
    } catch (ParseException e) {
      System.err.println("Parse exception while parsing line '" + line
          + "': " + e.getMessage() + ".  Skipping.");
      return;
    } catch (Exception e) {
      /* This catch block is only here to handle yet unknown errors.  It
       * should go away once we're sure what kind of errors can occur. */
      System.err.println("Unknown exception while parsing line '" + line
          + "': " + e.getMessage() + ".  Skipping.");
      return;
    }
    if (isRelay) {
      this.addRelay(nickname, fingerprint, address,
          orAddressesAndPorts, exitAddresses,
          publishedOrValidAfterMillis, orPort, dirPort, relayFlags,
          consensusWeight, countryCode, hostName, lastRdnsLookup,
          defaultPolicy, portList, firstSeenMillis,
          lastChangedAddresses, aSNumber);
    } else {
      this.addBridge(nickname, fingerprint, address,
          orAddressesAndPorts, exitAddresses,
          publishedOrValidAfterMillis, orPort, dirPort, relayFlags,
          consensusWeight, countryCode, hostName, lastRdnsLookup,
          defaultPolicy, portList, firstSeenMillis,
          lastChangedAddresses, aSNumber);
    }
  }

  public void writeStatusSummary() {
    String summaryString = this.writeSummaryString(true);
    this.documentStore.store(summaryString, DocumentType.STATUS_SUMMARY);
  }

  public void writeOutSummary() {
    String summaryString = this.writeSummaryString(false);
    this.documentStore.store(summaryString, DocumentType.OUT_SUMMARY);
    this.documentStore.store(String.valueOf(System.currentTimeMillis()),
        DocumentType.OUT_UPDATE);
  }

  /* Write internal relay search data to a string. */
  private String writeSummaryString(boolean includeOldNodes) {
    StringBuilder sb = new StringBuilder();
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    Collection<Node> relays = includeOldNodes
        ? this.knownRelays.values() : this.getCurrentRelays().values();
    for (Node entry : relays) {
      String nickname = entry.getNickname();
      String fingerprint = entry.getFingerprint();
      String address = entry.getAddress();
      StringBuilder addressesBuilder = new StringBuilder();
      addressesBuilder.append(address + ";");
      int written = 0;
      for (String orAddressAndPort : entry.getOrAddressesAndPorts()) {
        addressesBuilder.append((written++ > 0 ? "+" : "") +
            orAddressAndPort);
      }
      addressesBuilder.append(";");
      written = 0;
      for (String exitAddress : entry.getExitAddresses()) {
        addressesBuilder.append((written++ > 0 ? "+" : "")
            + exitAddress);
      }
      String lastSeen = dateTimeFormat.format(entry.getLastSeenMillis());
      String orPort = String.valueOf(entry.getOrPort());
      String dirPort = String.valueOf(entry.getDirPort());
      StringBuilder flagsBuilder = new StringBuilder();
      written = 0;
      for (String relayFlag : entry.getRelayFlags()) {
        flagsBuilder.append((written++ > 0 ? "," : "") + relayFlag);
      }
      String consensusWeight = String.valueOf(entry.getConsensusWeight());
      String countryCode = entry.getCountryCode() != null
          ? entry.getCountryCode() : "??";
      String hostName = entry.getHostName() != null
          ? entry.getHostName() : "null";
      long lastRdnsLookup = entry.getLastRdnsLookup();
      String defaultPolicy = entry.getDefaultPolicy() != null
          ? entry.getDefaultPolicy() : "null";
      String portList = entry.getPortList() != null
          ? entry.getPortList() : "null";
      String firstSeen = dateTimeFormat.format(
          entry.getFirstSeenMillis());
      String lastChangedAddresses = dateTimeFormat.format(
          entry.getLastChangedOrAddress());
      String aSNumber = entry.getASNumber() != null
          ? entry.getASNumber() : "null";
      sb.append("r " + nickname + " " + fingerprint + " "
          + addressesBuilder.toString() + " " + lastSeen + " "
          + orPort + " " + dirPort + " " + flagsBuilder.toString() + " "
          + consensusWeight + " " + countryCode + " " + hostName + " "
          + String.valueOf(lastRdnsLookup) + " " + defaultPolicy + " "
          + portList + " " + firstSeen + " " + lastChangedAddresses
          + " " + aSNumber + "\n");
    }
    Collection<Node> bridges = includeOldNodes
        ? this.knownBridges.values() : this.getCurrentBridges().values();
    for (Node entry : bridges) {
      String nickname = entry.getNickname();
      String fingerprint = entry.getFingerprint();
      String published = dateTimeFormat.format(
          entry.getLastSeenMillis());
      String address = entry.getAddress();
      StringBuilder addressesBuilder = new StringBuilder();
      addressesBuilder.append(address + ";");
      int written = 0;
      for (String orAddressAndPort : entry.getOrAddressesAndPorts()) {
        addressesBuilder.append((written++ > 0 ? "+" : "") +
            orAddressAndPort);
      }
      addressesBuilder.append(";");
      String orPort = String.valueOf(entry.getOrPort());
      String dirPort = String.valueOf(entry.getDirPort());
      StringBuilder flagsBuilder = new StringBuilder();
      written = 0;
      for (String relayFlag : entry.getRelayFlags()) {
        flagsBuilder.append((written++ > 0 ? "," : "") + relayFlag);
      }
      String firstSeen = dateTimeFormat.format(
          entry.getFirstSeenMillis());
      sb.append("b " + nickname + " " + fingerprint + " "
          + addressesBuilder.toString() + " " + published + " " + orPort
          + " " + dirPort + " " + flagsBuilder.toString()
          + " -1 ?? null -1 null null " + firstSeen + " null null "
          + "null\n");
    }
    return sb.toString();
  }

  private long lastValidAfterMillis = 0L;
  private long lastPublishedMillis = 0L;

  public void readRelayNetworkConsensuses() {
    if (this.descriptorSource == null) {
      System.err.println("Not configured to read relay network "
          + "consensuses.");
      return;
    }
    DescriptorQueue descriptorQueue =
        this.descriptorSource.getDescriptorQueue(
        DescriptorType.RELAY_CONSENSUSES,
        DescriptorHistory.RELAY_CONSENSUS_HISTORY);
    Descriptor descriptor;
    while ((descriptor = descriptorQueue.nextDescriptor()) != null) {
      if (descriptor instanceof RelayNetworkStatusConsensus) {
        updateRelayNetworkStatusConsensus(
            (RelayNetworkStatusConsensus) descriptor);
      }
    }
  }

  public void setRelayRunningBits() {
    if (this.lastValidAfterMillis > 0L) {
      for (Node entry : this.knownRelays.values()) {
        entry.setRunning(entry.getLastSeenMillis() ==
            this.lastValidAfterMillis);
      }
    }
  }

  SortedMap<String, Integer> lastBandwidthWeights = null;
  public SortedMap<String, Integer> getLastBandwidthWeights() {
    return this.lastBandwidthWeights;
  }
  private void updateRelayNetworkStatusConsensus(
      RelayNetworkStatusConsensus consensus) {
    long validAfterMillis = consensus.getValidAfterMillis();
    for (NetworkStatusEntry entry :
        consensus.getStatusEntries().values()) {
      String nickname = entry.getNickname();
      String fingerprint = entry.getFingerprint();
      String address = entry.getAddress();
      SortedSet<String> orAddressesAndPorts = new TreeSet<String>(
          entry.getOrAddresses());
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      SortedSet<String> relayFlags = entry.getFlags();
      long consensusWeight = entry.getBandwidth();
      String defaultPolicy = entry.getDefaultPolicy();
      String portList = entry.getPortList();
      this.addRelay(nickname, fingerprint, address, orAddressesAndPorts,
          null, validAfterMillis, orPort, dirPort, relayFlags,
          consensusWeight, null, null, -1L, defaultPolicy, portList,
          validAfterMillis, validAfterMillis, null);
    }
    if (this.lastValidAfterMillis == validAfterMillis) {
      this.lastBandwidthWeights = consensus.getBandwidthWeights();
    }
  }

  public void addRelay(String nickname, String fingerprint,
      String address, SortedSet<String> orAddressesAndPorts,
      SortedSet<String> exitAddresses, long lastSeenMillis, int orPort,
      int dirPort, SortedSet<String> relayFlags, long consensusWeight,
      String countryCode, String hostName, long lastRdnsLookup,
      String defaultPolicy, String portList, long firstSeenMillis,
      long lastChangedAddresses, String aSNumber) {
    /* Remember addresses and OR/dir ports that the relay advertised at
     * the given time. */
    SortedMap<Long, Set<String>> lastAddresses =
        new TreeMap<Long, Set<String>>(Collections.reverseOrder());
    Set<String> addresses = new HashSet<String>();
    addresses.add(address + ":" + orPort);
    if (dirPort > 0) {
      addresses.add(address + ":" + dirPort);
    }
    addresses.addAll(orAddressesAndPorts);
    lastAddresses.put(lastChangedAddresses, addresses);
    /* See if there's already an entry for this relay. */
    if (this.knownRelays.containsKey(fingerprint)) {
      Node existingEntry = this.knownRelays.get(fingerprint);
      if (lastSeenMillis < existingEntry.getLastSeenMillis()) {
        /* Use latest information for nickname, current addresses, etc. */
        nickname = existingEntry.getNickname();
        address = existingEntry.getAddress();
        orAddressesAndPorts = existingEntry.getOrAddressesAndPorts();
        exitAddresses = existingEntry.getExitAddresses();
        lastSeenMillis = existingEntry.getLastSeenMillis();
        orPort = existingEntry.getOrPort();
        dirPort = existingEntry.getDirPort();
        relayFlags = existingEntry.getRelayFlags();
        consensusWeight = existingEntry.getConsensusWeight();
        countryCode = existingEntry.getCountryCode();
        defaultPolicy = existingEntry.getDefaultPolicy();
        portList = existingEntry.getPortList();
      }
      if (hostName == null &&
          existingEntry.getAddress().equals(address)) {
        /* Re-use reverse DNS lookup results if available. */
        hostName = existingEntry.getHostName();
        lastRdnsLookup = existingEntry.getLastRdnsLookup();
      }
      /* Update relay-history fields. */
      firstSeenMillis = Math.min(firstSeenMillis,
          existingEntry.getFirstSeenMillis());
      lastAddresses.putAll(existingEntry.getLastAddresses());
    }
    /* Add or update entry. */
    Node entry = new Node(nickname, fingerprint, address,
        orAddressesAndPorts, exitAddresses, lastSeenMillis, orPort,
        dirPort, relayFlags, consensusWeight, countryCode, hostName,
        lastRdnsLookup, defaultPolicy, portList, firstSeenMillis,
        lastAddresses, aSNumber);
    this.knownRelays.put(fingerprint, entry);
    /* If this entry comes from a new consensus, update our global last
     * valid-after time. */
    if (lastSeenMillis > this.lastValidAfterMillis) {
      this.lastValidAfterMillis = lastSeenMillis;
    }
  }

  public void lookUpCitiesAndASes() {
    SortedSet<String> addressStrings = new TreeSet<String>();
    for (Node relay : this.knownRelays.values()) {
      addressStrings.add(relay.getAddress());
    }
    if (addressStrings.isEmpty()) {
      System.err.println("No relay IP addresses to resolve to cities or "
          + "ASN.");
      return;
    }
    SortedMap<String, LookupResult> lookupResults =
        this.lookupService.lookup(addressStrings);
    for (Node relay : knownRelays.values()) {
      String addressString = relay.getAddress();
      if (lookupResults.containsKey(addressString)) {
        LookupResult lookupResult = lookupResults.get(addressString);
        relay.setCountryCode(lookupResult.countryCode);
        relay.setCountryName(lookupResult.countryName);
        relay.setRegionName(lookupResult.regionName);
        relay.setCityName(lookupResult.cityName);
        relay.setLatitude(lookupResult.latitude);
        relay.setLongitude(lookupResult.longitude);
        relay.setASNumber(lookupResult.aSNumber);
        relay.setASName(lookupResult.aSName);
      }
    }
  }

  public void readBridgeNetworkStatuses() {
    if (this.descriptorSource == null) {
      System.err.println("Not configured to read bridge network "
          + "statuses.");
      return;
    }
    DescriptorQueue descriptorQueue =
        this.descriptorSource.getDescriptorQueue(
        DescriptorType.BRIDGE_STATUSES,
        DescriptorHistory.BRIDGE_STATUS_HISTORY);
    Descriptor descriptor;
    while ((descriptor = descriptorQueue.nextDescriptor()) != null) {
      if (descriptor instanceof BridgeNetworkStatus) {
        updateBridgeNetworkStatus((BridgeNetworkStatus) descriptor);
      }
    }
  }

  public void setBridgeRunningBits() {
    if (this.lastPublishedMillis > 0L) {
      for (Node entry : this.knownBridges.values()) {
        entry.setRunning(entry.getRelayFlags().contains("Running") &&
            entry.getLastSeenMillis() == this.lastPublishedMillis);
      }
    }
  }

  private void updateBridgeNetworkStatus(BridgeNetworkStatus status) {
    long publishedMillis = status.getPublishedMillis();
    for (NetworkStatusEntry entry : status.getStatusEntries().values()) {
      String nickname = entry.getNickname();
      String fingerprint = entry.getFingerprint();
      String address = entry.getAddress();
      SortedSet<String> orAddressesAndPorts = new TreeSet<String>(
          entry.getOrAddresses());
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      SortedSet<String> relayFlags = entry.getFlags();
      this.addBridge(nickname, fingerprint, address, orAddressesAndPorts,
          null, publishedMillis, orPort, dirPort, relayFlags, -1, "??",
          null, -1L, null, null, publishedMillis, -1L, null);
    }
  }

  public void addBridge(String nickname, String fingerprint,
      String address, SortedSet<String> orAddressesAndPorts,
      SortedSet<String> exitAddresses, long lastSeenMillis, int orPort,
      int dirPort, SortedSet<String> relayFlags, long consensusWeight,
      String countryCode, String hostname, long lastRdnsLookup,
      String defaultPolicy, String portList, long firstSeenMillis,
      long lastChangedAddresses, String aSNumber) {
    /* See if there's already an entry for this bridge. */
    if (this.knownBridges.containsKey(fingerprint)) {
      Node existingEntry = this.knownBridges.get(fingerprint);
      if (lastSeenMillis < existingEntry.getLastSeenMillis()) {
        /* Use latest information for nickname, current addresses, etc. */
        nickname = existingEntry.getNickname();
        address = existingEntry.getAddress();
        orAddressesAndPorts = existingEntry.getOrAddressesAndPorts();
        exitAddresses = existingEntry.getExitAddresses();
        lastSeenMillis = existingEntry.getLastSeenMillis();
        orPort = existingEntry.getOrPort();
        dirPort = existingEntry.getDirPort();
        relayFlags = existingEntry.getRelayFlags();
        consensusWeight = existingEntry.getConsensusWeight();
        countryCode = existingEntry.getCountryCode();
        defaultPolicy = existingEntry.getDefaultPolicy();
        portList = existingEntry.getPortList();
        aSNumber = existingEntry.getASNumber();
      }
      /* Update relay-history fields. */
      firstSeenMillis = Math.min(firstSeenMillis,
          existingEntry.getFirstSeenMillis());
    }
    /* Add or update entry. */
    Node entry = new Node(nickname, fingerprint, address,
        orAddressesAndPorts, exitAddresses, lastSeenMillis, orPort,
        dirPort, relayFlags, consensusWeight, countryCode, hostname,
        lastRdnsLookup, defaultPolicy, portList, firstSeenMillis, null,
        aSNumber);
    this.knownBridges.put(fingerprint, entry);
    /* If this entry comes from a new status, update our global last
     * published time. */
    if (lastSeenMillis > this.lastPublishedMillis) {
      this.lastPublishedMillis = lastSeenMillis;
    }
  }

  private SortedMap<String, Node> knownRelays =
      new TreeMap<String, Node>();
  public SortedMap<String, Node> getCurrentRelays() {
    long cutoff = this.lastValidAfterMillis
        - 7L * 24L * 60L * 60L * 1000L;
    SortedMap<String, Node> currentRelays = new TreeMap<String, Node>();
    for (Map.Entry<String, Node> e : this.knownRelays.entrySet()) {
      if (e.getValue().getLastSeenMillis() >= cutoff) {
        currentRelays.put(e.getKey(), e.getValue());
      }
    }
    return currentRelays;
  }

  private SortedMap<String, Node> knownBridges =
      new TreeMap<String, Node>();
  public SortedMap<String, Node> getCurrentBridges() {
    long cutoff = this.lastPublishedMillis - 7L * 24L * 60L * 60L * 1000L;
    SortedMap<String, Node> currentBridges = new TreeMap<String, Node>();
    for (Map.Entry<String, Node> e : this.knownBridges.entrySet()) {
      if (e.getValue().getLastSeenMillis() >= cutoff) {
        currentBridges.put(e.getKey(), e.getValue());
      }
    }
    return currentBridges;
  }

  public long getLastValidAfterMillis() {
    return this.lastValidAfterMillis;
  }

  public long getLastPublishedMillis() {
    return this.lastPublishedMillis;
  }
}

