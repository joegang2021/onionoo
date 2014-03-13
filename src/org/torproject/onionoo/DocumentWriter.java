/* Copyright 2014 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

public interface DocumentWriter {

  public abstract void writeDocuments();

  public abstract String getStatsString();
}

