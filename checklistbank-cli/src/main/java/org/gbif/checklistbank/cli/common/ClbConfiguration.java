package org.gbif.checklistbank.cli.common;

import org.gbif.checklistbank.service.mybatis.guice.ChecklistBankServiceMyBatisModule;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;
import javax.validation.constraints.NotNull;

import com.beust.jcommander.Parameter;
import com.google.common.collect.Sets;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A configuration for the checklist bank database connection pool
 * as used by the mybatis layer. Knows how to insert a service guice module.
 */
@SuppressWarnings("PublicField")
public class ClbConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ClbConfiguration.class);
  private static final String PROPERTY_PREFIX = "checklistbank.db.";
  private static final Set<String> DATASOURCE_SET = Sets.newHashSet("serverName","databaseName","user","password");
  private static final Set<String> IGNORE = Sets.newHashSet("parserTimeout");

  @NotNull
  @Parameter(names = "--clb-host")
  public String serverName;

  @NotNull
  @Parameter(names = "--clb-db")
  public String databaseName;

  @NotNull
  @Parameter(names = "--clb-user")
  public String user;

  @NotNull
  @Parameter(names = "--clb-password", password = true)
  public String password;

  @Parameter(names = "--clb-maximumPoolSize")
  public int maximumPoolSize = 8;

  @Parameter(names = "--clb-connectionTimeout")
  public int connectionTimeout = 5000;

  @Parameter(names = "--parser-timeout")
  public int parserTimeout = 1000;


  public ChecklistBankServiceMyBatisModule createServiceModule() {
    Properties props = new Properties();
    props.put(PROPERTY_PREFIX + "dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
    props.put(ChecklistBankServiceMyBatisModule.PARSER_TIMEOUT_PROP, String.valueOf(parserTimeout));

    for (Field field : ClbConfiguration.class.getDeclaredFields()) {
      if (!field.isSynthetic() && Modifier.isPublic(field.getModifiers())) {
        try {
          if (IGNORE.contains(field.getName())) {
            // ignore
          } else if (DATASOURCE_SET.contains(field.getName())) {
            props.put(PROPERTY_PREFIX + "dataSource." + field.getName(), String.valueOf(field.get(this)));
          } else {
            props.put(PROPERTY_PREFIX + field.getName(), String.valueOf(field.get(this)));
          }
        } catch (IllegalAccessException e) {
          // cant happen, we check for public access
          throw new RuntimeException(e);
        }
      }
    }
    LOG.info("Connecting to checklistbank db {} on {} with user {}", databaseName, serverName, user);
    return new ChecklistBankServiceMyBatisModule(props);
  }

  /**
   * @return a new simple postgres jdbc connection
   */
  public BaseConnection connect() throws SQLException {
    String url = "jdbc:postgresql://" + serverName + "/" + databaseName;
    return (BaseConnection) DriverManager.getConnection(url, user, password);
  }

}
