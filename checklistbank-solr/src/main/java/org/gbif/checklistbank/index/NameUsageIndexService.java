package org.gbif.checklistbank.index;

import org.gbif.api.model.checklistbank.NameUsage;
import org.gbif.checklistbank.model.UsageExtensions;

import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Real time solr service for checklistbank.
 */
public interface NameUsageIndexService {

  void delete(int usageKey);

  void delete(UUID datasetKey);

  void insertOrUpdate(int usageKey);

  void insertOrUpdate(NameUsage usage, List<Integer> parentKeys, @Nullable UsageExtensions extensions);
}
