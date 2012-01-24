/* Copyright 2011, 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.io.*;
import java.text.*;
import java.util.*;
import org.torproject.descriptor.*;

/* Write detail data files to disk and delete status files of relays or
 * bridges that fell out the search data list. */
public class DetailDataWriter {

  private SortedMap<String, Node> relays;
  public void setCurrentRelays(SortedMap<String, Node> relays) {
    this.relays = relays;
  }
  private SortedMap<String, Node> bridges;
  public void setCurrentBridges(SortedMap<String, Node> bridges) {
    this.bridges = bridges;
  }

  private Map<String, ServerDescriptor> relayServerDescriptors =
      new HashMap<String, ServerDescriptor>();
  public void readRelayServerDescriptors() {
    RelayDescriptorReader reader =
        DescriptorSourceFactory.createRelayDescriptorReader();
    reader.addDirectory(new File(
        "in/relay-descriptors/server-descriptors"));
    reader.setExcludeFiles(new File("status/relay-serverdesc-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          ServerDescriptor serverDescriptor =
              (ServerDescriptor) descriptor;
          String fingerprint = serverDescriptor.getFingerprint();
          if (!this.relayServerDescriptors.containsKey(fingerprint) ||
              this.relayServerDescriptors.get(fingerprint).
              getPublishedMillis()
              < serverDescriptor.getPublishedMillis()) {
            /* TODO This makes us pick the last published server
             * descriptor per relay, not the one that is referenced in the
             * consensus.  This may not be what we want. */
            this.relayServerDescriptors.put(fingerprint,
                serverDescriptor);
          }
        }
      }
    }
  }

  private Map<String, ServerDescriptor> bridgeServerDescriptors =
      new HashMap<String, ServerDescriptor>();
  public void readBridgeServerDescriptors() {
    RelayDescriptorReader reader =
        DescriptorSourceFactory.createRelayDescriptorReader();
    reader.addDirectory(new File(
        "in/bridge-descriptors/server-descriptors"));
    reader.setExcludeFiles(new File("status/bridge-serverdesc-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          ServerDescriptor serverDescriptor =
              (ServerDescriptor) descriptor;
          String fingerprint = serverDescriptor.getFingerprint();
          if (!this.bridgeServerDescriptors.containsKey(fingerprint) ||
              this.bridgeServerDescriptors.get(fingerprint).
              getPublishedMillis()
              < serverDescriptor.getPublishedMillis()) {
            /* TODO This makes us pick the last published server
             * descriptor per relay, not the one that is referenced in the
             * consensus.  This may not be what we want. */
            this.bridgeServerDescriptors.put(fingerprint,
                serverDescriptor);
          }
        }
      }
    }
  }

  private Map<String, String> bridgePoolAssignments =
      new HashMap<String, String>();
  public void readBridgePoolAssignments() {
    BridgePoolAssignmentReader reader =
        DescriptorSourceFactory.createBridgePoolAssignmentReader();
    reader.addDirectory(new File("in/bridge-pool-assignments"));
    reader.setExcludeFiles(new File("status/bridge-poolassign-history"));
    Iterator<DescriptorFile> descriptorFiles = reader.readDescriptors();
    while (descriptorFiles.hasNext()) {
      DescriptorFile descriptorFile = descriptorFiles.next();
      if (descriptorFile.getDescriptors() != null) {
        for (Descriptor descriptor : descriptorFile.getDescriptors()) {
          BridgePoolAssignment bridgePoolAssignment =
              (BridgePoolAssignment) descriptor;
          for (Map.Entry<String, String> e :
              bridgePoolAssignment.getEntries().entrySet()) {
            String fingerprint = e.getKey();
            String details = e.getValue();
            /* TODO Should we check that assignments don't change over
             * time? */
            this.bridgePoolAssignments.put(fingerprint, details);
          }
        }
      }
    }
  }

  public void writeDetailDataFiles() {
    SortedMap<String, File> remainingStatusFiles =
        this.listAllStatusFiles();
    remainingStatusFiles = this.updateRelayStatusFiles(
        remainingStatusFiles);
    remainingStatusFiles = this.updateBridgeStatusFiles(
        remainingStatusFiles);
    this.deleteStatusFiles(remainingStatusFiles);
  }

  private File statusFileDirectory = new File("out/details");
  private SortedMap<String, File> listAllStatusFiles() {
    SortedMap<String, File> result = new TreeMap<String, File>();
    if (statusFileDirectory.exists() &&
        statusFileDirectory.isDirectory()) {
      for (File file : statusFileDirectory.listFiles()) {
        if (file.getName().length() == 40) {
          result.put(file.getName(), file);
        }
      }
    }
    return result;
  }

  private SortedMap<String, File> updateRelayStatusFiles(
      SortedMap<String, File> remainingStatusFiles) {
    SortedMap<String, File> result =
        new TreeMap<String, File>(remainingStatusFiles);
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    for (Map.Entry<String, Node> relay : this.relays.entrySet()) {
      String fingerprint = relay.getKey();

      /* Read status file for this relay if it exists. */
      String descriptorParts = null;
      long publishedMillis = -1L;
      if (result.containsKey(fingerprint)) {
        File statusFile = result.remove(fingerprint);
        try {
          BufferedReader br = new BufferedReader(new FileReader(
              statusFile));
          String line;
          boolean copyLines = false;
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null) {
            if (line.startsWith("\"desc_published\":")) {
              String published = line.substring(
                  "\"desc_published\":\"".length(),
                  "\"desc_published\":\"1970-01-01 00:00:00".length());
              publishedMillis = dateTimeFormat.parse(published).getTime();
              copyLines = true;
            }
            if (copyLines) {
              sb.append(line + "\n");
            }
          }
          br.close();
          descriptorParts = sb.toString();
        } catch (IOException e) {
          System.err.println("Could not read '"
              + statusFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        } catch (ParseException e) {
          System.err.println("Could not read '"
              + statusFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        }
      }

      /* Generate new descriptor-specific part if we have a more recent
       * descriptor. */
      if (this.relayServerDescriptors.containsKey(fingerprint) &&
          this.relayServerDescriptors.get(fingerprint).
          getPublishedMillis() > publishedMillis) {
        ServerDescriptor descriptor = this.relayServerDescriptors.get(
            fingerprint);
        StringBuilder sb = new StringBuilder();
        String publishedDateTime = dateTimeFormat.format(
            descriptor.getPublishedMillis());
        int advertisedBandwidth = Math.min(descriptor.getBandwidthRate(),
            Math.min(descriptor.getBandwidthBurst(),
            descriptor.getBandwidthObserved()));
        sb.append("\"desc_published\":\"" + publishedDateTime + "\",\n"
            + "\"uptime\":" + descriptor.getUptime() + ",\n"
            + "\"advertised_bandwidth\":" + advertisedBandwidth + ",\n"
            + "\"exit_policy\":[");
        int written = 0;
        for (String exitPolicyLine : descriptor.getExitPolicyLines()) {
          sb.append((written++ > 0 ? "," : "") + "\n  \"" + exitPolicyLine
              + "\"");
        }
        sb.append("\n]");
        if (descriptor.getContact() != null) {
          sb.append(",\n\"contact\":\"" + descriptor.getContact() + "\"");
        }
        if (descriptor.getPlatform() != null) {
          sb.append(",\n\"platform\":\"" + descriptor.getPlatform()
              + "\"");
        }
        if (descriptor.getFamilyEntries() != null) {
          sb.append(",\n\"family\":[");
          written = 0;
          for (String familyEntry : descriptor.getFamilyEntries()) {
            sb.append((written++ > 0 ? "," : "") + "\n  \"" + familyEntry
                + "\"");
          }
          sb.append("\n]");
        }
        sb.append("\n}\n");
        descriptorParts = sb.toString();
      }

      /* Generate network-status-specific part. */
      Node entry = relay.getValue();
      String nickname = entry.getNickname();
      String address = entry.getAddress();
      String running = entry.getRunning() ? "true" : "false";
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      String country = entry.getCountry();
      StringBuilder sb = new StringBuilder();
      sb.append("{\"version\":1,\n"
          + "\"nickname\":\"" + nickname + "\",\n"
          + "\"fingerprint\":\"" + fingerprint + "\",\n"
          + "\"or_address\":[\"" + address + "\"],\n"
          + "\"or_port\":" + orPort + ",\n"
          + "\"dir_port\":" + dirPort + ",\n"
          + "\"running\":" + running + ",\n");
      SortedSet<String> relayFlags = entry.getRelayFlags();
      if (!relayFlags.isEmpty()) {
        sb.append("\"flags\":[");
        int written = 0;
        for (String relayFlag : relayFlags) {
          sb.append((written++ > 0 ? "," : "") + "\"" + relayFlag + "\"");
        }
        sb.append("]");
      }
      if (country != null) {
        sb.append(",\n\"country\":\"" + country + "\"");
      }
      String statusParts = sb.toString();

      /* Write status file to disk. */
      File statusFile = new File(statusFileDirectory, fingerprint);
      try {
        statusFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            statusFile));
        bw.write(statusParts);
        if (descriptorParts != null) {
          bw.write(",\n" + descriptorParts);
        } else {
          bw.write("\n}\n");
        }
        bw.close();
      } catch (IOException e) {
        System.err.println("Could not write status file '"
            + statusFile.getAbsolutePath() + "'.  This file may now be "
            + "broken.  Ignoring.");
        e.printStackTrace();
      }
    }

    /* Return the files that we didn't update. */
    return result;
  }

  private SortedMap<String, File> updateBridgeStatusFiles(
      SortedMap<String, File> remainingStatusFiles) {
    SortedMap<String, File> result =
        new TreeMap<String, File>(remainingStatusFiles);
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    for (Map.Entry<String, Node> bridge : this.bridges.entrySet()) {
      String fingerprint = bridge.getKey();

      /* Read details file for this bridge if it exists. */
      String descriptorParts = null, bridgePoolAssignment = null;
      long publishedMillis = -1L;
      if (result.containsKey(fingerprint)) {
        File statusFile = result.remove(fingerprint);
        try {
          BufferedReader br = new BufferedReader(new FileReader(
              statusFile));
          String line;
          boolean copyDescriptorParts = false;
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null) {
            if (line.startsWith("\"desc_published\":")) {
              String published = line.substring(
                  "\"desc_published\":\"".length(),
                  "\"desc_published\":\"1970-01-01 00:00:00".length());
              publishedMillis = dateTimeFormat.parse(published).getTime();
              copyDescriptorParts = true;
            } else if (line.startsWith("\"pool_assignment\":")) {
              bridgePoolAssignment = line;
              copyDescriptorParts = false;
            } else if (line.equals("}")) {
              copyDescriptorParts = false;
            }
            if (copyDescriptorParts) {
              sb.append(line + "\n");
            }
          }
          br.close();
          descriptorParts = sb.toString();
          if (descriptorParts.endsWith(",\n")) {
            descriptorParts = descriptorParts.substring(0,
                descriptorParts.length() - 2);
          } else if (descriptorParts.endsWith("\n")) {
            descriptorParts = descriptorParts.substring(0,
                descriptorParts.length() - 1);
          }
        } catch (IOException e) {
          System.err.println("Could not read '"
              + statusFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        } catch (ParseException e) {
          System.err.println("Could not read '"
              + statusFile.getAbsolutePath() + "'.  Skipping");
          e.printStackTrace();
          publishedMillis = -1L;
          descriptorParts = null;
        }
      }

      /* Generate new descriptor-specific part if we have a more recent
       * descriptor. */
      if (this.bridgeServerDescriptors.containsKey(fingerprint) &&
          this.bridgeServerDescriptors.get(fingerprint).
          getPublishedMillis() > publishedMillis) {
        ServerDescriptor descriptor = this.bridgeServerDescriptors.get(
            fingerprint);
        StringBuilder sb = new StringBuilder();
        String publishedDateTime = dateTimeFormat.format(
            descriptor.getPublishedMillis());
        int advertisedBandwidth = Math.min(descriptor.getBandwidthRate(),
            Math.min(descriptor.getBandwidthBurst(),
            descriptor.getBandwidthObserved()));
        sb.append("\"desc_published\":\"" + publishedDateTime + "\",\n"
            + "\"uptime\":" + descriptor.getUptime() + ",\n"
            + "\"advertised_bandwidth\":" + advertisedBandwidth + ",\n"
            + "\"exit_policy\":[");
        int written = 0;
        for (String exitPolicyLine : descriptor.getExitPolicyLines()) {
          sb.append((written++ > 0 ? "," : "") + "\n  \"" + exitPolicyLine
              + "\"");
        }
        sb.append("\n],\n\"platform\":\"" + descriptor.getPlatform()
            + "\"");
        if (descriptor.getFamilyEntries() != null) {
          sb.append(",\n\"family\":[");
          written = 0;
          for (String familyEntry : descriptor.getFamilyEntries()) {
            sb.append((written++ > 0 ? "," : "") + "\n  \"" + familyEntry
                + "\"");
          }
          sb.append("\n]");
        }
        descriptorParts = sb.toString();
      }

      /* Generate bridge pool assignment if we don't have one yet. */
      /* TODO Should we check that assignments don't change over time? */
      if (bridgePoolAssignment == null &&
          this.bridgePoolAssignments.containsKey(fingerprint)) {
        bridgePoolAssignment = "\"pool_assignment\":\""
            + this.bridgePoolAssignments.get(fingerprint) + "\"";
      }

      /* Generate network-status-specific part. */
      Node entry = bridge.getValue();
      String running = entry.getRunning() ? "true" : "false";
      int orPort = entry.getOrPort();
      int dirPort = entry.getDirPort();
      StringBuilder sb = new StringBuilder();
      sb.append("{\"version\":1,\n"
          + "\"hashed_fingerprint\":\"" + fingerprint + "\",\n"
          + "\"or_port\":" + orPort + ",\n"
          + "\"dir_port\":" + dirPort + ",\n"
          + "\"running\":" + running + ",");
      SortedSet<String> relayFlags = entry.getRelayFlags();
      if (!relayFlags.isEmpty()) {
        sb.append("\n\"flags\":[");
        int written = 0;
        for (String relayFlag : relayFlags) {
          sb.append((written++ > 0 ? "," : "") + "\"" + relayFlag + "\"");
        }
        sb.append("]");
      }

      /* Append descriptor and bridge pool assignment parts. */
      if (descriptorParts != null) {
        sb.append(",\n" + descriptorParts);
      }
      if (bridgePoolAssignment != null) {
        sb.append(",\n" + bridgePoolAssignment);
      }
      sb.append("\n}\n");
      String detailsLines = sb.toString();

      /* Write status file to disk. */
      File statusFile = new File(statusFileDirectory, fingerprint);
      try {
        statusFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            statusFile));
        bw.write(detailsLines);
        bw.close();
      } catch (IOException e) {
        System.err.println("Could not write details file '"
            + statusFile.getAbsolutePath() + "'.  This file may now be "
            + "broken.  Ignoring.");
        e.printStackTrace();
      }
    }

    /* Return the files that we didn't update. */
    return result;
  }

  private void deleteStatusFiles(
      SortedMap<String, File> remainingStatusFiles) {
    for (File statusFile : remainingStatusFiles.values()) {
      statusFile.delete();
    }
  }
}
