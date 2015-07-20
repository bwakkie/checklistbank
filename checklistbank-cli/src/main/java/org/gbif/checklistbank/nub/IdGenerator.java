package org.gbif.checklistbank.nub;

import org.gbif.api.vocabulary.Kingdom;
import org.gbif.api.vocabulary.Rank;
import org.gbif.checklistbank.nub.lookup.IdLookup;
import org.gbif.checklistbank.nub.lookup.LookupUsage;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nub id generator trying to reuse previously existing ids, even if they had been deleted.
 * It will only ever issue the same id once.
 */
public class IdGenerator implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(IdGenerator.class);
    private IdLookup lookup;
    private final int idStart;
    private int nextId;
    private IntSet resurrected = new IntHashSet();
    private IntSet reissued = new IntHashSet();
    private final File fdeleted;
    private final FileWriter fresurrected;
    private final FileWriter fcreated;
    Joiner nameJoiner = Joiner.on(" ").skipNulls();

    public IdGenerator(IdLookup lookup, int idStart, File reportDir) {
        this.lookup = lookup;
        this.idStart = idStart;
        nextId = idStart;
        fdeleted = new File(reportDir, "deleted.txt");
        try {
            fdeleted.getParentFile().mkdirs();
            fresurrected = new FileWriter(new File(reportDir, "resurrected.txt"));
            fcreated = new FileWriter(new File(reportDir, "created.txt"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public int issue(String canonicalName, String authorship, String year, Rank rank, Kingdom kingdom) {
        LookupUsage u = lookup.match(canonicalName, authorship, year, rank, kingdom);
        int id = -1;
        try {
            if (u == null) {
                id = nextId++;
                logName(fcreated, u);
            } else if (reissued.contains(u.getKey()) || resurrected.contains(u.getKey())) {
                id = nextId++;
                LOG.warn("{} {} {} was already issued as {}. Generating new id {} instead", kingdom, rank, canonicalName, u.getKey(), id);
                logName(fcreated, u);
            } else {
                id = u.getKey();
                if (u.isDeleted()) {
                    resurrected.add(id);
                    logName(fresurrected, u);
                } else {
                    reissued.add(id);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to write idgenerator report log");
        }
        return id;
    }

    private void logName(Writer writer, LookupUsage u) throws IOException {
        writer.write(u.getKey() + '\t' + nameJoiner.join(u.getCanonical(), u.getAuthorship(), u.getYear()) + '\n');
    }

    @Override
    public void close() throws IOException {
        fresurrected.close();
        fcreated.close();
        try (FileWriter delwriter = new FileWriter(fdeleted)) {
            for (LookupUsage u : lookup) {
                if (!u.isDeleted() && !reissued.contains(u.getKey())) {
                    logName(delwriter, u);
                }
            }
        }
    }

    /**
     * Reports all ids that have changed between 2 backbone builds.
     */
    public class Metrics {
        /**
         * Newly reissued nub ids that did not exist before.
         */
        public final IntSet created;

        /**
         * Resurrecetd nub ids that existed before but had been assigned to deleted usages in the old nub.
         */
        public final IntSet resurrected;

        /**
         * Deleted nub ids that existed before but have been removed from the new backbone.
         */
        public final IntSet deleted;

        public Metrics(IntSet created, IntSet resurrected, IntSet deleted) {
            this.created = created;
            this.resurrected = resurrected;
            this.deleted = deleted;
        }

        @Override
        public String toString() {
            return "Metrics{" +
                    "created=" + created.size() +
                    ", resurrected=" + resurrected.size() +
                    ", deleted=" + deleted.size() +
                    '}';
        }
    }

    /**
     * Builds a new metrics report.
     * This is a little costly, so dont use this in debugging!
     */
    public Metrics metrics() {
        IntSet created = new IntHashSet(nextId-idStart);
        for (int id=idStart; id<nextId; id++) {
            created.add(id);
        }
        IntSet deleted = new IntHashSet(lookup.deletedIds());
        for (LookupUsage u : lookup) {
            if (!u.isDeleted() && !reissued.contains(u.getKey())) {
                deleted.add(u.getKey());
            }
        }
        return new Metrics(created, resurrected, deleted);
    }

}
