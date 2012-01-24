/* Copyright 2011, 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.util.*;

/* Store search data of a single relay that was running in the past seven
 * days. */
public class Node {
  private String fingerprint;
  private String nickname;
  private String address;
  private String country;
  private long lastSeenMillis;
  private int orPort;
  private int dirPort;
  private SortedSet<String> relayFlags;
  private boolean running;
  public Node(String nickname, String fingerprint, String address,
      long lastSeenMillis, int orPort, int dirPort,
      SortedSet<String> relayFlags) {
    this.nickname = nickname;
    this.fingerprint = fingerprint;
    this.address = address;
    this.lastSeenMillis = lastSeenMillis;
    this.orPort = orPort;
    this.dirPort = dirPort;
    this.relayFlags = relayFlags;
  }
  public Node(String fingerprint, long lastSeenMillis, int orPort,
      int dirPort, SortedSet<String> relayFlags) {
    this(null, fingerprint, null, lastSeenMillis, orPort, dirPort,
        relayFlags);
  }
  public String getFingerprint() {
    return this.fingerprint;
  }
  public String getNickname() {
    return this.nickname;
  }
  public String getAddress() {
    return this.address;
  }
  public void setCountry(String country) {
    this.country = country;
  }
  public String getCountry() {
    return this.country;
  }
  public long getLastSeenMillis() {
    return this.lastSeenMillis;
  }
  public int getOrPort() {
    return this.orPort;
  }
  public int getDirPort() {
    return this.dirPort;
  }
  public SortedSet<String> getRelayFlags() {
    return this.relayFlags;
  }
  public void setRunning(boolean running) {
    this.running = running;
  }
  public boolean getRunning() {
    return this.running;
  }
}
