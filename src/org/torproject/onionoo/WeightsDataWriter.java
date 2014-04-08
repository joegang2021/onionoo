/* Copyright 2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.onionoo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.torproject.descriptor.Descriptor;
import org.torproject.descriptor.NetworkStatusEntry;
import org.torproject.descriptor.RelayNetworkStatusConsensus;
import org.torproject.descriptor.ServerDescriptor;

public class WeightsDataWriter implements DescriptorListener,
    StatusUpdater, FingerprintListener, DocumentWriter {

  private DescriptorSource descriptorSource;

  private DocumentStore documentStore;

  private long now;

  public WeightsDataWriter(DescriptorSource descriptorSource,
      DocumentStore documentStore, Time time) {
    this.descriptorSource = descriptorSource;
    this.documentStore = documentStore;
    this.now = time.currentTimeMillis();
    this.registerDescriptorListeners();
    this.registerFingerprintListeners();
  }

  private void registerDescriptorListeners() {
    this.descriptorSource.registerDescriptorListener(this,
        DescriptorType.RELAY_CONSENSUSES);
    this.descriptorSource.registerDescriptorListener(this,
        DescriptorType.RELAY_SERVER_DESCRIPTORS);
  }

  private void registerFingerprintListeners() {
    this.descriptorSource.registerFingerprintListener(this,
        DescriptorType.RELAY_CONSENSUSES);
    this.descriptorSource.registerFingerprintListener(this,
        DescriptorType.RELAY_SERVER_DESCRIPTORS);
  }

  public void processDescriptor(Descriptor descriptor, boolean relay) {
    if (descriptor instanceof ServerDescriptor) {
      this.processRelayServerDescriptor((ServerDescriptor) descriptor);
    } else if (descriptor instanceof RelayNetworkStatusConsensus) {
      this.processRelayNetworkConsensus(
          (RelayNetworkStatusConsensus) descriptor);
    }
  }

  public void updateStatuses() {
    this.updateWeightsHistories();
    Logger.printStatusTime("Updated weights histories");
    this.updateWeightsStatuses();
    Logger.printStatusTime("Updated weights status files");
  }

  public void writeDocuments() {
    this.writeWeightsDataFiles();
    Logger.printStatusTime("Wrote weights document files");
  }

  private Set<RelayNetworkStatusConsensus> consensuses =
      new HashSet<RelayNetworkStatusConsensus>();

  private void processRelayNetworkConsensus(
      RelayNetworkStatusConsensus consensus) {
    this.consensuses.add(consensus);
  }

  private Set<String> updateWeightsStatuses = new HashSet<String>();

  private Set<String> updateWeightsDocuments = new HashSet<String>();

  private Map<String, Set<String>> descriptorDigestsByFingerprint =
      new HashMap<String, Set<String>>();

  private Map<String, Integer> advertisedBandwidths =
      new HashMap<String, Integer>();

  private void processRelayServerDescriptor(
      ServerDescriptor serverDescriptor) {
    String digest = serverDescriptor.getServerDescriptorDigest().
        toUpperCase();
    int advertisedBandwidth = Math.min(Math.min(
        serverDescriptor.getBandwidthBurst(),
        serverDescriptor.getBandwidthObserved()),
        serverDescriptor.getBandwidthRate());
    this.advertisedBandwidths.put(digest, advertisedBandwidth);
    String fingerprint = serverDescriptor.getFingerprint();
    this.updateWeightsStatuses.add(fingerprint);
    if (!this.descriptorDigestsByFingerprint.containsKey(
        fingerprint)) {
      this.descriptorDigestsByFingerprint.put(fingerprint,
          new HashSet<String>());
    }
    this.descriptorDigestsByFingerprint.get(fingerprint).add(digest);
  }

  private void updateWeightsHistories() {
    for (RelayNetworkStatusConsensus consensus : this.consensuses) {
      long validAfterMillis = consensus.getValidAfterMillis(),
          freshUntilMillis = consensus.getFreshUntilMillis();
      SortedMap<String, double[]> pathSelectionWeights =
          this.calculatePathSelectionProbabilities(consensus);
      this.updateWeightsHistory(validAfterMillis, freshUntilMillis,
          pathSelectionWeights);
    }
  }

  // TODO Use 4 workers once threading problems are solved.
  private static final int HISTORY_UPDATER_WORKERS_NUM = 1;
  private void updateWeightsHistory(long validAfterMillis,
      long freshUntilMillis,
      SortedMap<String, double[]> pathSelectionWeights) {
    List<HistoryUpdateWorker> historyUpdateWorkers =
        new ArrayList<HistoryUpdateWorker>();
    for (int i = 0; i < HISTORY_UPDATER_WORKERS_NUM; i++) {
      HistoryUpdateWorker historyUpdateWorker =
          new HistoryUpdateWorker(validAfterMillis, freshUntilMillis,
          pathSelectionWeights, this);
      historyUpdateWorkers.add(historyUpdateWorker);
      historyUpdateWorker.setDaemon(true);
      historyUpdateWorker.start();
    }
    for (HistoryUpdateWorker historyUpdateWorker : historyUpdateWorkers) {
      try {
        historyUpdateWorker.join();
      } catch (InterruptedException e) {
        /* This is not something that we can take care of.  Just leave the
         * worker thread alone. */
      }
    }
  }

  private class HistoryUpdateWorker extends Thread {
    private long validAfterMillis;
    private long freshUntilMillis;
    private SortedMap<String, double[]> pathSelectionWeights;
    private WeightsDataWriter parent;
    public HistoryUpdateWorker(long validAfterMillis,
        long freshUntilMillis,
        SortedMap<String, double[]> pathSelectionWeights,
        WeightsDataWriter parent) {
      this.validAfterMillis = validAfterMillis;
      this.freshUntilMillis = freshUntilMillis;
      this.pathSelectionWeights = pathSelectionWeights;
      this.parent = parent;
    }
    public void run() {
      String fingerprint = null;
      double[] weights = null;
      do {
        fingerprint = null;
        synchronized (pathSelectionWeights) {
          if (!pathSelectionWeights.isEmpty()) {
            fingerprint = pathSelectionWeights.firstKey();
            weights = pathSelectionWeights.remove(fingerprint);
          }
        }
        if (fingerprint != null) {
          this.parent.addToHistory(fingerprint, this.validAfterMillis,
              this.freshUntilMillis, weights);
        }
      } while (fingerprint != null);
    }
  }

  private SortedMap<String, double[]> calculatePathSelectionProbabilities(
      RelayNetworkStatusConsensus consensus) {
    double wgg = 1.0, wgd = 1.0, wmg = 1.0, wmm = 1.0, wme = 1.0,
        wmd = 1.0, wee = 1.0, wed = 1.0;
    SortedMap<String, Integer> bandwidthWeights =
        consensus.getBandwidthWeights();
    if (bandwidthWeights != null) {
      SortedSet<String> missingWeightKeys = new TreeSet<String>(
          Arrays.asList("Wgg,Wgd,Wmg,Wmm,Wme,Wmd,Wee,Wed".split(",")));
      missingWeightKeys.removeAll(bandwidthWeights.keySet());
      if (missingWeightKeys.isEmpty()) {
        wgg = ((double) bandwidthWeights.get("Wgg")) / 10000.0;
        wgd = ((double) bandwidthWeights.get("Wgd")) / 10000.0;
        wmg = ((double) bandwidthWeights.get("Wmg")) / 10000.0;
        wmm = ((double) bandwidthWeights.get("Wmm")) / 10000.0;
        wme = ((double) bandwidthWeights.get("Wme")) / 10000.0;
        wmd = ((double) bandwidthWeights.get("Wmd")) / 10000.0;
        wee = ((double) bandwidthWeights.get("Wee")) / 10000.0;
        wed = ((double) bandwidthWeights.get("Wed")) / 10000.0;
      }
    }
    SortedMap<String, Double>
        advertisedBandwidths = new TreeMap<String, Double>(),
        consensusWeights = new TreeMap<String, Double>(),
        guardWeights = new TreeMap<String, Double>(),
        middleWeights = new TreeMap<String, Double>(),
        exitWeights = new TreeMap<String, Double>();
    double totalAdvertisedBandwidth = 0.0;
    double totalConsensusWeight = 0.0;
    double totalGuardWeight = 0.0;
    double totalMiddleWeight = 0.0;
    double totalExitWeight = 0.0;
    for (NetworkStatusEntry relay :
        consensus.getStatusEntries().values()) {
      String fingerprint = relay.getFingerprint();
      if (!relay.getFlags().contains("Running")) {
        continue;
      }
      boolean isExit = relay.getFlags().contains("Exit") &&
          !relay.getFlags().contains("BadExit");
      boolean isGuard = relay.getFlags().contains("Guard");
      String serverDescriptorDigest = relay.getDescriptor().
          toUpperCase();
      double advertisedBandwidth = 0.0;
      if (!this.advertisedBandwidths.containsKey(
          serverDescriptorDigest)) {
        WeightsStatus weightsStatus = this.documentStore.retrieve(
            WeightsStatus.class, true, fingerprint);
        if (weightsStatus != null) {
          if (!this.descriptorDigestsByFingerprint.containsKey(
              fingerprint)) {
            this.descriptorDigestsByFingerprint.put(fingerprint,
                new HashSet<String>());
          }
          this.descriptorDigestsByFingerprint.get(fingerprint).addAll(
              weightsStatus.advertisedBandwidths.keySet());
          this.advertisedBandwidths.putAll(
              weightsStatus.advertisedBandwidths);
        }
      }
      if (this.advertisedBandwidths.containsKey(
          serverDescriptorDigest)) {
        advertisedBandwidth = (double) this.advertisedBandwidths.get(
            serverDescriptorDigest);
      }
      double consensusWeight = (double) relay.getBandwidth();
      double guardWeight = (double) relay.getBandwidth();
      double middleWeight = (double) relay.getBandwidth();
      double exitWeight = (double) relay.getBandwidth();
      if (isGuard && isExit) {
        guardWeight *= wgd;
        middleWeight *= wmd;
        exitWeight *= wed;
      } else if (isGuard) {
        guardWeight *= wgg;
        middleWeight *= wmg;
        exitWeight = 0.0;
      } else if (isExit) {
        guardWeight = 0.0;
        middleWeight *= wme;
        exitWeight *= wee;
      } else {
        guardWeight = 0.0;
        middleWeight *= wmm;
        exitWeight = 0.0;
      }
      advertisedBandwidths.put(fingerprint, advertisedBandwidth);
      consensusWeights.put(fingerprint, consensusWeight);
      guardWeights.put(fingerprint, guardWeight);
      middleWeights.put(fingerprint, middleWeight);
      exitWeights.put(fingerprint, exitWeight);
      totalAdvertisedBandwidth += advertisedBandwidth;
      totalConsensusWeight += consensusWeight;
      totalGuardWeight += guardWeight;
      totalMiddleWeight += middleWeight;
      totalExitWeight += exitWeight;
    }
    SortedMap<String, double[]> pathSelectionProbabilities =
        new TreeMap<String, double[]>();
    for (NetworkStatusEntry relay :
        consensus.getStatusEntries().values()) {
      String fingerprint = relay.getFingerprint();
      double[] probabilities = new double[] {
          advertisedBandwidths.get(fingerprint)
            / totalAdvertisedBandwidth,
          consensusWeights.get(fingerprint) / totalConsensusWeight,
          guardWeights.get(fingerprint) / totalGuardWeight,
          middleWeights.get(fingerprint) / totalMiddleWeight,
          exitWeights.get(fingerprint) / totalExitWeight };
      pathSelectionProbabilities.put(fingerprint, probabilities);
    }
    return pathSelectionProbabilities;
  }

  private void addToHistory(String fingerprint, long validAfterMillis,
      long freshUntilMillis, double[] weights) {
    WeightsStatus weightsStatus = this.documentStore.retrieve(
        WeightsStatus.class, true, fingerprint);
    if (weightsStatus == null) {
      weightsStatus = new WeightsStatus();
    }
    SortedMap<long[], double[]> history = weightsStatus.history;
    long[] interval = new long[] { validAfterMillis, freshUntilMillis };
    if ((history.headMap(interval).isEmpty() ||
        history.headMap(interval).lastKey()[1] <= validAfterMillis) &&
        (history.tailMap(interval).isEmpty() ||
        history.tailMap(interval).firstKey()[0] >= freshUntilMillis)) {
      history.put(interval, weights);
      this.compressHistory(weightsStatus);
      this.addAdvertisedBandwidths(weightsStatus, fingerprint);
      this.documentStore.store(weightsStatus, fingerprint);
      this.updateWeightsStatuses.remove(fingerprint);
    }
  }

  private void addAdvertisedBandwidths(WeightsStatus weightsStatus,
      String fingerprint) {
    if (this.descriptorDigestsByFingerprint.containsKey(fingerprint)) {
      for (String descriptorDigest :
          this.descriptorDigestsByFingerprint.get(fingerprint)) {
        if (this.advertisedBandwidths.containsKey(descriptorDigest)) {
          int advertisedBandwidth =
              this.advertisedBandwidths.get(descriptorDigest);
          weightsStatus.advertisedBandwidths.put(descriptorDigest,
              advertisedBandwidth);
        }
      }
    }
  }

  private void compressHistory(WeightsStatus weightsStatus) {
    SortedMap<long[], double[]> history = weightsStatus.history;
    SortedMap<long[], double[]> compressedHistory =
        new TreeMap<long[], double[]>(history.comparator());
    long lastStartMillis = 0L, lastEndMillis = 0L;
    double[] lastWeights = null;
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    String lastMonthString = "1970-01";
    for (Map.Entry<long[], double[]> e : history.entrySet()) {
      long startMillis = e.getKey()[0], endMillis = e.getKey()[1];
      double[] weights = e.getValue();
      long intervalLengthMillis;
      if (this.now - endMillis <= 7L * 24L * 60L * 60L * 1000L) {
        intervalLengthMillis = 60L * 60L * 1000L;
      } else if (this.now - endMillis <= 31L * 24L * 60L * 60L * 1000L) {
        intervalLengthMillis = 4L * 60L * 60L * 1000L;
      } else if (this.now - endMillis <= 92L * 24L * 60L * 60L * 1000L) {
        intervalLengthMillis = 12L * 60L * 60L * 1000L;
      } else if (this.now - endMillis <= 366L * 24L * 60L * 60L * 1000L) {
        intervalLengthMillis = 2L * 24L * 60L * 60L * 1000L;
      } else {
        intervalLengthMillis = 10L * 24L * 60L * 60L * 1000L;
      }
      String monthString = dateTimeFormat.format(startMillis);
      if (lastEndMillis == startMillis &&
          ((lastEndMillis - 1L) / intervalLengthMillis) ==
          ((endMillis - 1L) / intervalLengthMillis) &&
          lastMonthString.equals(monthString)) {
        double lastIntervalInHours = (double) ((lastEndMillis
            - lastStartMillis) / 60L * 60L * 1000L);
        double currentIntervalInHours = (double) ((endMillis
            - startMillis) / 60L * 60L * 1000L);
        double newIntervalInHours = (double) ((endMillis
            - lastStartMillis) / 60L * 60L * 1000L);
        for (int i = 0; i < lastWeights.length; i++) {
          lastWeights[i] *= lastIntervalInHours;
          lastWeights[i] += weights[i] * currentIntervalInHours;
          lastWeights[i] /= newIntervalInHours;
        }
        lastEndMillis = endMillis;
      } else {
        if (lastStartMillis > 0L) {
          compressedHistory.put(new long[] { lastStartMillis,
              lastEndMillis }, lastWeights);
        }
        lastStartMillis = startMillis;
        lastEndMillis = endMillis;
        lastWeights = weights;
      }
      lastMonthString = monthString;
    }
    if (lastStartMillis > 0L) {
      compressedHistory.put(new long[] { lastStartMillis, lastEndMillis },
          lastWeights);
    }
    weightsStatus.history = compressedHistory;
  }

  public void processFingerprints(SortedSet<String> fingerprints,
      boolean relay) {
    if (relay) {
      this.updateWeightsDocuments.addAll(fingerprints);
    }
  }

  private void writeWeightsDataFiles() {
    for (String fingerprint : this.updateWeightsDocuments) {
      WeightsStatus weightsStatus = this.documentStore.retrieve(
          WeightsStatus.class, true, fingerprint);
      if (weightsStatus == null) {
        continue;
      }
      SortedMap<long[], double[]> history = weightsStatus.history;
      WeightsDocument weightsDocument = new WeightsDocument();
      weightsDocument.documentString = this.formatHistoryString(
          fingerprint, history);
      this.documentStore.store(weightsDocument, fingerprint);
    }
    Logger.printStatusTime("Wrote weights document files");
  }

  private String[] graphTypes = new String[] {
      "advertised_bandwidth_fraction",
      "consensus_weight_fraction",
      "guard_probability",
      "middle_probability",
      "exit_probability"
  };

  private String[] graphNames = new String[] {
      "1_week",
      "1_month",
      "3_months",
      "1_year",
      "5_years" };

  private long[] graphIntervals = new long[] {
      7L * 24L * 60L * 60L * 1000L,
      31L * 24L * 60L * 60L * 1000L,
      92L * 24L * 60L * 60L * 1000L,
      366L * 24L * 60L * 60L * 1000L,
      5L * 366L * 24L * 60L * 60L * 1000L };

  private long[] dataPointIntervals = new long[] {
      60L * 60L * 1000L,
      4L * 60L * 60L * 1000L,
      12L * 60L * 60L * 1000L,
      2L * 24L * 60L * 60L * 1000L,
      10L * 24L * 60L * 60L * 1000L };

  private String formatHistoryString(String fingerprint,
      SortedMap<long[], double[]> history) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"fingerprint\":\"" + fingerprint + "\"");
    for (int graphTypeIndex = 0; graphTypeIndex < this.graphTypes.length;
        graphTypeIndex++) {
      String graphType = this.graphTypes[graphTypeIndex];
      sb.append(",\n\"" + graphType + "\":{");
      int graphIntervalsWritten = 0;
      for (int graphIntervalIndex = 0; graphIntervalIndex <
          this.graphIntervals.length; graphIntervalIndex++) {
        String timeline = this.formatTimeline(graphTypeIndex,
            graphIntervalIndex, history);
        if (timeline != null) {
          sb.append((graphIntervalsWritten++ > 0 ? "," : "") + "\n"
            + timeline);
        }
      }
      sb.append("}");
    }
    sb.append("\n}\n");
    return sb.toString();
  }

  private String formatTimeline(int graphTypeIndex,
      int graphIntervalIndex, SortedMap<long[], double[]> history) {
    String graphName = this.graphNames[graphIntervalIndex];
    long graphInterval = this.graphIntervals[graphIntervalIndex];
    long dataPointInterval =
        this.dataPointIntervals[graphIntervalIndex];
    List<Double> dataPoints = new ArrayList<Double>();
    long intervalStartMillis = ((this.now - graphInterval)
        / dataPointInterval) * dataPointInterval;
    long totalMillis = 0L;
    double totalWeightTimesMillis = 0.0;
    for (Map.Entry<long[], double[]> e : history.entrySet()) {
      long startMillis = e.getKey()[0], endMillis = e.getKey()[1];
      double weight = e.getValue()[graphTypeIndex];
      if (endMillis < intervalStartMillis) {
        continue;
      }
      while ((intervalStartMillis / dataPointInterval) !=
          (endMillis / dataPointInterval)) {
        dataPoints.add(totalMillis * 5L < dataPointInterval
            ? -1.0 : totalWeightTimesMillis / (double) totalMillis);
        totalWeightTimesMillis = 0.0;
        totalMillis = 0L;
        intervalStartMillis += dataPointInterval;
      }
      totalWeightTimesMillis += weight
          * ((double) (endMillis - startMillis));
      totalMillis += (endMillis - startMillis);
    }
    dataPoints.add(totalMillis * 5L < dataPointInterval
        ? -1.0 : totalWeightTimesMillis / (double) totalMillis);
    double maxValue = 0.0;
    int firstNonNullIndex = -1, lastNonNullIndex = -1;
    for (int dataPointIndex = 0; dataPointIndex < dataPoints.size();
        dataPointIndex++) {
      double dataPoint = dataPoints.get(dataPointIndex);
      if (dataPoint >= 0.0) {
        if (firstNonNullIndex < 0) {
          firstNonNullIndex = dataPointIndex;
        }
        lastNonNullIndex = dataPointIndex;
        if (dataPoint > maxValue) {
          maxValue = dataPoint;
        }
      }
    }
    if (firstNonNullIndex < 0) {
      return null;
    }
    long firstDataPointMillis = (((this.now - graphInterval)
        / dataPointInterval) + firstNonNullIndex) * dataPointInterval
        + dataPointInterval / 2L;
    if (graphIntervalIndex > 0 && firstDataPointMillis >=
        this.now - graphIntervals[graphIntervalIndex - 1]) {
      /* Skip weights history object, because it doesn't contain
       * anything new that wasn't already contained in the last
       * weights history object(s). */
      return null;
    }
    long lastDataPointMillis = firstDataPointMillis
        + (lastNonNullIndex - firstNonNullIndex) * dataPointInterval;
    double factor = ((double) maxValue) / 999.0;
    int count = lastNonNullIndex - firstNonNullIndex + 1;
    StringBuilder sb = new StringBuilder();
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    sb.append("\"" + graphName + "\":{"
        + "\"first\":\"" + dateTimeFormat.format(firstDataPointMillis)
        + "\",\"last\":\"" + dateTimeFormat.format(lastDataPointMillis)
        + "\",\"interval\":" + String.valueOf(dataPointInterval / 1000L)
        + ",\"factor\":" + String.format(Locale.US, "%.9f", factor)
        + ",\"count\":" + String.valueOf(count) + ",\"values\":[");
    int dataPointsWritten = 0, previousNonNullIndex = -2;
    boolean foundTwoAdjacentDataPoints = false;
    for (int dataPointIndex = firstNonNullIndex; dataPointIndex <=
        lastNonNullIndex; dataPointIndex++) {
      double dataPoint = dataPoints.get(dataPointIndex);
      if (dataPoint >= 0.0) {
        if (dataPointIndex - previousNonNullIndex == 1) {
          foundTwoAdjacentDataPoints = true;
        }
        previousNonNullIndex = dataPointIndex;
      }
      sb.append((dataPointsWritten++ > 0 ? "," : "")
          + (dataPoint < 0.0 ? "null" :
          String.valueOf((long) ((dataPoint * 999.0) / maxValue))));
    }
    sb.append("]}");
    if (foundTwoAdjacentDataPoints) {
      return sb.toString();
    } else {
      return null;
    }
  }

  private void updateWeightsStatuses() {
    for (String fingerprint : this.updateWeightsStatuses) {
      WeightsStatus weightsStatus = this.documentStore.retrieve(
          WeightsStatus.class, true, fingerprint);
      if (weightsStatus == null) {
        weightsStatus = new WeightsStatus();
      }
      this.addAdvertisedBandwidths(weightsStatus, fingerprint);
      this.documentStore.store(weightsStatus, fingerprint);
    }
  }

  public String getStatsString() {
    /* TODO Add statistics string. */
    return null;
  }
}

