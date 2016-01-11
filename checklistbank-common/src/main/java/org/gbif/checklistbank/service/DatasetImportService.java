package org.gbif.checklistbank.service;

import org.gbif.api.model.checklistbank.NameUsage;
import org.gbif.checklistbank.model.UsageForeignKeys;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Persistence service dealing with methods needed to import new checklists into checklistbank.
 * The methods are mostly doing batch operations and larger operations, hardly any single record modifications.
 * This interface is restricted to the mybatis module only!
 */
public interface DatasetImportService extends AutoCloseable {

  Future<Boolean> sync(UUID datasetKey, ImporterCallback dao, Iterable<Integer> usages);

  Future<Boolean> sync(UUID datasetKey, List<NameUsage> usages);

  Future<Boolean> updateForeignKeys(List<UsageForeignKeys> fks);

  /**
   * Delete all existing nub relations and then batch insert new ones from the passed map.
   * All dataset usages should be covered by the passed map and impossible nub matches should have a null value.
   * This will lead to NameUsageIssue.BACKBONE_MATCH_NONE being added to the usages issue set.
   *
   * This is a synchroneous call and on return all relations are guaranteed to be updated.
   *
   * @param datasetKey the datasource to map to the nub
   * @param relations  map from source usage id to a nub usage id for all usages in a dataset. Values can be null to indicate a NameUsageIssue.BACKBONE_MATCH_NONE
   */
  void insertNubRelations(UUID datasetKey, Map<Integer, Integer> relations);

  /**
   * Remove entire dataset from checklistbank
   * @return number of deleted usage records
   */
  int deleteDataset(UUID datasetKey);

  Future<Boolean> deleteUsages(UUID datasetKey, List<Integer> usageKeys);

  /**
   * @return true if there is still a running import task
   */
  boolean isRunning();

}
