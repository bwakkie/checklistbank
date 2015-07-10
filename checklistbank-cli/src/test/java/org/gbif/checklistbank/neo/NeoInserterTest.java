package org.gbif.checklistbank.neo;

import org.gbif.api.model.checklistbank.NameUsage;
import org.gbif.api.model.checklistbank.NameUsageContainer;
import org.gbif.api.model.checklistbank.VerbatimNameUsage;
import org.gbif.api.vocabulary.NameType;
import org.gbif.api.vocabulary.Rank;
import org.gbif.checklistbank.cli.normalizer.IgnoreNameUsageException;
import org.gbif.dwc.terms.DcTerm;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwca.io.ArchiveField;
import org.gbif.dwca.record.RecordImpl;
import org.gbif.dwca.record.StarRecordImpl;

import java.net.URI;
import java.util.List;

import com.beust.jcommander.internal.Lists;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NeoInserterTest {
  NeoInserter ins;
  List<String> values;
  List<ArchiveField> fields;
  UsageDao dao;

  @Before
  public void init() throws Exception {
    values = Lists.newArrayList();
    fields = Lists.newArrayList();
    dao = UsageDao.temporaryDao(10);
    ins = dao.createBatchInserter(100);
  }

  private void addColumn(Term term, String value) {
    ArchiveField f = new ArchiveField(fields.size(), term, null, ArchiveField.DataType.string);
    fields.add(f);
    values.add(value);
  }

  @Test
  public void testNonDwcProperties() throws Exception {

    addColumn(DwcTerm.taxonID, "1");
    addColumn(DwcTerm.scientificName, "Abies alba Mill., 1982");
    addColumn(DwcTerm.scientificNameAuthorship, "Mill.");
    addColumn(DwcTerm.namePublishedIn, "Führ. Pilzk. (Zwickau) 136 (1871)");
    addColumn(DwcTerm.namePublishedInID, "12345678");
    addColumn(DwcTerm.namePublishedInYear, "1871");
    addColumn(DwcTerm.nameAccordingTo, "Miller");
    addColumn(DwcTerm.nameAccordingToID, "123456");
    addColumn(DwcTerm.taxonRemarks, "bugger off");
    addColumn(DcTerm.references, "http://gbif.org");

    StarRecordImpl star = new StarRecordImpl(Lists.<Term>newArrayList());
    RecordImpl rec = new RecordImpl(fields.get(0), fields, DwcTerm.Taxon, true, true);
    rec.setRow(values.toArray(new String[] {}));
    star.newCoreRecord(rec);
    ins.insertStarRecord(star);
    ins.close();

    try (Transaction tx = dao.beginTx()) {
      Node n = dao.getNeo().getNodeById(0);
      assertEquals("1", n.getProperty(NodeProperties.TAXON_ID));

      VerbatimNameUsage v = dao.readVerbatim(n.getId());
      assertEquals("1", v.getCoreField(DwcTerm.taxonID));
      assertEquals("Abies alba Mill., 1982", v.getCoreField(DwcTerm.scientificName));
      assertEquals("Mill.", v.getCoreField(DwcTerm.scientificNameAuthorship));
      assertEquals("Führ. Pilzk. (Zwickau) 136 (1871)", v.getCoreField(DwcTerm.namePublishedIn));
      assertEquals("12345678", v.getCoreField(DwcTerm.namePublishedInID));
      assertEquals("1871", v.getCoreField(DwcTerm.namePublishedInYear));
      assertEquals("Miller", v.getCoreField(DwcTerm.nameAccordingTo));
      assertEquals("123456", v.getCoreField(DwcTerm.nameAccordingToID));
      assertEquals("bugger off", v.getCoreField(DwcTerm.taxonRemarks));
      assertEquals("http://gbif.org", v.getCoreField(DcTerm.references));

      NameUsage u = dao.readUsage(n, true);
      assertEquals("1", u.getTaxonID());
      assertEquals("Abies alba Mill., 1982", u.getScientificName());
      assertEquals("Mill.", u.getAuthorship());
      assertEquals("Führ. Pilzk. (Zwickau) 136 (1871)", u.getPublishedIn());
      assertEquals("Miller", u.getAccordingTo());
      assertEquals("bugger off", u.getRemarks());
      assertEquals(URI.create("http://gbif.org"), u.getReferences());
    }
  }

  @Test
  public void testSetScientificName() throws Exception {

    VerbatimNameUsage v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.scientificName, "Abies alba Mill., 1982");
    assertName(v, Rank.SPECIES, "Abies alba Mill., 1982", "Abies alba", NameType.WELLFORMED);

    v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.scientificName, "Abies alba");
    assertName(v, Rank.SPECIES, "Abies alba", "Abies alba", NameType.WELLFORMED);

    v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.scientificName, "? alba");
    assertName(v, Rank.SPECIES, "? alba", null, NameType.DOUBTFUL);

    v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.genus, "Abies");
    v.setCoreField(DwcTerm.specificEpithet, "alba");
    assertName(v, Rank.SPECIES, "Abies alba", "Abies alba", NameType.WELLFORMED);

    v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.genus, "Abies");
    v.setCoreField(DwcTerm.infraspecificEpithet, "alpina");
    assertName(v, Rank.SUBSPECIES, "Abies subsp.", "Abies subsp.", NameType.WELLFORMED);

    v = new VerbatimNameUsage();
    v.setCoreField(DwcTerm.genus, "Abies");
    v.setCoreField(DwcTerm.specificEpithet, "alba");
    v.setCoreField(DwcTerm.infraspecificEpithet, "alpina");
    assertName(v, Rank.SUBSPECIES, "Abies alba subsp. alpina", "Abies alba alpina", NameType.WELLFORMED);

    v.setCoreField(DwcTerm.scientificNameAuthorship, "Duméril & Bibron");
    assertName(v, Rank.SUBSPECIES, "Abies alba subsp. alpina Duméril & Bibron", "Abies alba alpina", NameType.WELLFORMED);

    v.setCoreField(DwcTerm.namePublishedInYear, "1937");
    assertName(v, Rank.SUBSPECIES, "Abies alba subsp. alpina Duméril & Bibron, 1937", "Abies alba alpina", NameType.WELLFORMED);

    v.setCoreField(DwcTerm.scientificNameAuthorship, "Duméril & Bibron 1937");
    assertName(v, Rank.SUBSPECIES, "Abies alba subsp. alpina Duméril & Bibron 1937", "Abies alba alpina", NameType.WELLFORMED);
  }

  @Test(expected = IgnoreNameUsageException.class)
  public void testSetScientificNameExc() throws Exception {
    VerbatimNameUsage v = new VerbatimNameUsage();
    assertName(v, Rank.SPECIES, null, null, NameType.BLACKLISTED);
  }

  private NameUsageContainer assertName(VerbatimNameUsage v, Rank rank, String sciname, String canonical, NameType ntype)
    throws IgnoreNameUsageException {
    NameUsageContainer u = new NameUsageContainer();
    ins.setScientificName(u, v, rank);
    if (sciname != null) {
      assertEquals(sciname, u.getScientificName());
    } else {
      assertNull(u.getScientificName());
    }
    if (canonical != null) {
      assertEquals(canonical, u.getCanonicalName());
    } else {
      assertNull(u.getCanonicalName());
    }
    if (ntype != null) {
      assertEquals(ntype, u.getNameType());
    } else {
      assertNull(u.getNameType());
    }
    return u;
  }

  @Test
  public void testClean() throws Exception {
    assertNull(NeoInserter.clean(null));
    assertNull(NeoInserter.clean(" "));
    assertNull(NeoInserter.clean("   "));
    assertNull(NeoInserter.clean("\\N"));
    assertNull(NeoInserter.clean("NULL"));
    assertNull(NeoInserter.clean("\t "));

    assertEquals("Abies", NeoInserter.clean("Abies"));
    assertEquals("öAbies", NeoInserter.clean("öAbies"));
    assertEquals("Abies  mille", NeoInserter.clean(" Abies  mille"));
  }
}