package org.gbif.checklistbank.cli.admin;

import org.gbif.api.model.checklistbank.NameUsage;
import org.gbif.api.model.crawler.DwcaValidationReport;
import org.gbif.api.model.crawler.GenericValidationReport;
import org.gbif.api.model.registry.Dataset;
import org.gbif.api.service.registry.DatasetService;
import org.gbif.api.service.registry.InstallationService;
import org.gbif.api.service.registry.NetworkService;
import org.gbif.api.service.registry.NodeService;
import org.gbif.api.service.registry.OrganizationService;
import org.gbif.api.util.iterables.Iterables;
import org.gbif.api.vocabulary.DatasetType;
import org.gbif.checklistbank.cli.common.ZookeeperUtils;
import org.gbif.checklistbank.cli.registry.RegistryService;
import org.gbif.checklistbank.neo.UsageDao;
import org.gbif.checklistbank.nub.model.NubUsage;
import org.gbif.checklistbank.service.ParsedNameService;
import org.gbif.checklistbank.service.mybatis.ParsedNameServiceMyBatis;
import org.gbif.checklistbank.service.mybatis.mapper.DatasetMapper;
import org.gbif.cli.BaseCommand;
import org.gbif.cli.Command;
import org.gbif.common.messaging.DefaultMessagePublisher;
import org.gbif.common.messaging.api.Message;
import org.gbif.common.messaging.api.MessagePublisher;
import org.gbif.common.messaging.api.messages.ChecklistNormalizedMessage;
import org.gbif.common.messaging.api.messages.ChecklistSyncedMessage;
import org.gbif.common.messaging.api.messages.DwcaMetasyncFinishedMessage;
import org.gbif.common.messaging.api.messages.StartCrawlMessage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.UUID;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.commons.io.FileUtils;
import org.kohsuke.MetaInfServices;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command that issues new normalize or import messages for manual admin purposes.
 */
@MetaInfServices(Command.class)
public class AdminCommand extends BaseCommand {
    private static final Logger LOG = LoggerFactory.getLogger(AdminCommand.class);
    private static final String DWCA_SUFFIX = ".dwca";

    private final AdminConfiguration cfg = new AdminConfiguration();
    private MessagePublisher publisher;
    private ZookeeperUtils zkUtils;
    private DatasetService datasetService;
    private OrganizationService organizationService;
    private InstallationService installationService;
    private NetworkService networkService;
    private NodeService nodeService;
    private Iterable<Dataset> datasets;

    public AdminCommand() {
        super("admin");
    }

    @Override
    protected Object getConfigurationObject() {
        return cfg;
    }

    private void initRegistry() {
        Injector inj = cfg.registry.createRegistryInjector();
        datasetService = inj.getInstance(DatasetService.class);
        organizationService = inj.getInstance(OrganizationService.class);
        installationService = inj.getInstance(InstallationService.class);
        networkService = inj.getInstance(NetworkService.class);
        nodeService = inj.getInstance(NodeService.class);
    }

    private ZookeeperUtils zk() {
        if (zkUtils == null) {
            try {
                zkUtils = new ZookeeperUtils(cfg.zookeeper.getCuratorFramework());
            } catch (IOException e) {
                Throwables.propagate(e);
            }
        }
        return zkUtils;
    }

    private void send(Message msg) throws IOException {
        if (publisher == null) {
            publisher = new DefaultMessagePublisher(cfg.messaging.getConnectionParameters());
        }
        publisher.send(msg);
    }

    @Override
    protected void doRun() {
        initRegistry();
        if (Sets.newHashSet(AdminOperation.REPARSE, AdminOperation.CLEAN_ORPHANS, AdminOperation.SYNC_DATASETS, AdminOperation.SHOW).contains(cfg.operation)) {
            runSineDatasets();
        } else {
            runDatasetComamnds();
        }
    }

    private void runSineDatasets() {
        switch (cfg.operation) {
            case REPARSE:
                reparseNames();
                break;

            case CLEAN_ORPHANS:
                cleanOrphans();
                break;

            case SHOW:
                show(cfg.key);
                break;

            case SYNC_DATASETS:
                syncDatasets();
                break;

            default:
                throw new UnsupportedOperationException();
        }
    }

    private void syncDatasets() {
        initRegistry();
        Injector inj = Guice.createInjector(cfg.clb.createMapperModule());
        DatasetMapper mapper = inj.getInstance(DatasetMapper.class);
        LOG.info("Start syncing datasets from registry to CLB.");
        int counter = 0;
        Iterable<Dataset> datasets = Iterables.datasets(DatasetType.CHECKLIST, datasetService);
        mapper.truncate();
        for (Dataset d : datasets) {
            mapper.insert(d.getKey(), d.getTitle());
            counter++;
        }
        LOG.info("{} checklist titles copied", counter);
    }

    /**
     * Cleans up orphan records in the postgres db.
     */
    private void cleanOrphans() {
        Injector inj = Guice.createInjector(cfg.clb.createServiceModule());
        ParsedNameServiceMyBatis parsedNameService = (ParsedNameServiceMyBatis) inj.getInstance(ParsedNameService.class);
        LOG.info("Start cleaning up orphan names. This will take a while ...");
        int num = parsedNameService.deleteOrphaned();
        LOG.info("{} orphan names deleted", num);
    }

    private void runDatasetComamnds() {
        if (cfg.keys != null) {
            datasets = com.google.common.collect.Iterables.transform(cfg.listKeys(), new Function<UUID, Dataset>() {
                @Nullable
                @Override
                public Dataset apply(UUID key) {
                    return datasetService.get(key);
                }
            });
        } else {
            datasets = Iterables.datasets(cfg.key, cfg.type, datasetService, organizationService, installationService, networkService, nodeService);
        }

        for (Dataset d : datasets) {
            LOG.info("{} {} dataset {}: {}", cfg.operation, d.getType(), d.getKey(), d.getTitle().replaceAll("\n", " "));
            try {
                switch (cfg.operation) {
                    case CLEANUP:
                        zk().delete(ZookeeperUtils.getCrawlInfoPath(d.getKey(), null));
                        LOG.info("Removed crawl {} from zookeeper", d.getKey());

                        // cleanup repo files
                        final File dwcaFile = new File(cfg.archiveRepository, d.getKey() + DWCA_SUFFIX);
                        FileUtils.deleteQuietly(dwcaFile);
                        File dir = cfg.archiveDir(d.getKey());
                        if (dir.exists() && dir.isDirectory()) {
                            FileUtils.deleteDirectory(dir);
                        }
                        LOG.info("Removed dwca files from repository {}", dwcaFile);

                        RegistryService.deleteStorageFiles(cfg.neo, d.getKey());
                        break;

                    case CRAWL:
                        send(new StartCrawlMessage(d.getKey()));
                        break;

                    case NORMALIZE:
                        if (!cfg.archiveDir(d.getKey()).exists()) {
                            LOG.info("Missing dwca file. Cannot normalize dataset {}", title(d));
                        } else {
                            // validation result is a fake valid checklist validation
                            send(new DwcaMetasyncFinishedMessage(d.getKey(), d.getType(),
                                            URI.create("http://fake.org"), 1, Maps.<String, UUID>newHashMap(),
                                            new DwcaValidationReport(d.getKey(),
                                                    new GenericValidationReport(1, true, Lists.<String>newArrayList(), Lists.<Integer>newArrayList()))
                                    )
                            );
                        }
                        break;

                    case IMPORT:
                        if (!cfg.neo.neoDir(d.getKey()).exists()) {
                            LOG.info("Missing neo4j directory. Cannot import dataset {}", title(d));
                        } else {
                            send(new ChecklistNormalizedMessage(d.getKey()));
                        }
                        break;

                    case ANALYZE:
                        send(new ChecklistSyncedMessage(d.getKey(), new Date(), 0, 0));
                        break;

                    default:
                        throw new UnsupportedOperationException();
                }

            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void show(UUID datasetKey) {
        UsageDao dao = UsageDao.persistentDao(cfg.neo, datasetKey, true, null, false);
        try (Transaction tx = dao.beginTx()) {
            if (cfg.usageKey != null) {
                Node n = dao.getNeo().getNodeById(cfg.usageKey);

                NubUsage nub = dao.readNub(n);
                System.out.println("NUB: " + nub.toStringComplete());

                NameUsage u = dao.readUsage(n, true);
                System.out.println("USAGE: " + u);

            } else {
                // show entire tree
                dao.logStats();
                dao.printTree();
            }
        } finally {
            dao.close();
        }
    }

    /**
     * Reparses all names
     */
    private void reparseNames() {
        Injector inj = Guice.createInjector(cfg.clb.createServiceModule());
        ParsedNameService parsedNameService = inj.getInstance(ParsedNameService.class);
        LOG.info("Start reparsing all names. This will take a while ...");
        int num = parsedNameService.reparseAll();
        LOG.info("{} names reparsed", num);
    }


    private String title(Dataset d) {
        return d.getKey() + ": " + d.getTitle().replaceAll("\n", " ");
    }
}
