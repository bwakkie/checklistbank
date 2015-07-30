package org.gbif.checklistbank.service.mybatis.mapper;

import org.gbif.api.model.checklistbank.NameUsageMetrics;
import org.gbif.api.model.checklistbank.ParsedName;
import org.gbif.api.vocabulary.NameType;
import org.gbif.api.vocabulary.Rank;
import org.gbif.api.vocabulary.TaxonomicStatus;
import org.gbif.checklistbank.model.NameUsageWritable;
import org.gbif.checklistbank.service.mybatis.postgres.MybatisMapperTestRule;

import java.util.UUID;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NameUsageMetricsMapperIT {

    private static final UUID DATASET_KEY = UUID.randomUUID();

    private ParsedNameMapper nameMapper;
    private NameUsageMapper usageMapper;

    @Rule
    public MybatisMapperTestRule<NameUsageMetricsMapper> ddt = MybatisMapperTestRule.empty(NameUsageMetricsMapper.class, "name", "name_usage", "name_usage_metrics");

    @Before
    public void setup() {
        nameMapper = ddt.getInjector().getInstance(ParsedNameMapper.class);
        usageMapper = ddt.getInjector().getInstance(NameUsageMapper.class);
    }

    private int createUsage(String name) {
        // name first
        ParsedName pn = nameMapper.getByName(name);
        if (pn == null) {
            pn = new ParsedName();
            pn.setType(NameType.WELLFORMED);
            pn.setScientificName(name);
            nameMapper.create(pn, name);
        }
        // name usage
        NameUsageWritable nu = new NameUsageWritable();
        nu.setDatasetKey(DATASET_KEY);
        nu.setRank(Rank.SPECIES);
        nu.setTaxonomicStatus(TaxonomicStatus.ACCEPTED);
        nu.setSynonym(false);
        nu.setNameKey(pn.getKey());
        nu.setNumDescendants(100);
        usageMapper.insert(nu);
        return nu.getKey();
    }

    /**
     * Check all enum values have a matching postgres type value.
     */
    @Test
    public void testInsertAndRead() {
        String name = "Abies alba Mill.";
        int usageKey = createUsage(name);

        NameUsageMetrics m = new NameUsageMetrics();
        m.setKey(usageKey);
        m.setNumChildren(1);
        m.setNumClass(2);
        m.setNumDescendants(3);
        m.setNumFamily(4);
        m.setNumGenus(5);
        m.setNumOrder(6);
        m.setNumPhylum(7);
        m.setNumSpecies(8);
        m.setNumSubgenus(9);
        m.setNumSynonyms(10);

        ddt.getService().insert(DATASET_KEY, m);

        NameUsageMetrics m2 = ddt.getService().get(usageKey);
        assertNotEquals(m2, m);

        // this should be ignored and taken from the name_usage instead on reads
        m.setNumDescendants(100);
        assertEquals(m2, m);
    }

}