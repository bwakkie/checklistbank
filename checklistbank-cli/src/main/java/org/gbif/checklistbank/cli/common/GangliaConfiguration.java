package org.gbif.checklistbank.cli.common;

import java.util.concurrent.TimeUnit;

import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;
import com.yammer.metrics.MetricRegistry;
import com.yammer.metrics.ganglia.GangliaReporter;
import info.ganglia.gmetric4j.gmetric.GMetric;

/**
 * A configuration class which holds the host and port to connect yammer metrics to a ganglia server.
 */
@SuppressWarnings("PublicField")
public class GangliaConfiguration {

  @Parameter(names = "--ganglia-host")
  public String gangliaHost;

  @Parameter(names = "--ganglia-port")
  public int gangliaPort;

  /**
   * Starts the GangliaReporter, pointing to the configured host and port.
   */
  @JsonIgnore
  public void start(MetricRegistry registry) {
    if (gangliaHost != null && gangliaPort > 0) {
      final GMetric ganglia = new GMetric(gangliaHost, gangliaPort, GMetric.UDPAddressingMode.MULTICAST, 1);
      final GangliaReporter reporter = GangliaReporter.forRegistry(registry)
        .convertRatesTo(TimeUnit.SECONDS)
        .convertDurationsTo(TimeUnit.MILLISECONDS)
        .build(ganglia);
      reporter.start(1, TimeUnit.MINUTES);
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this).add("gangliaHost", gangliaHost).add("gangliaPort", gangliaPort).toString();
  }
}