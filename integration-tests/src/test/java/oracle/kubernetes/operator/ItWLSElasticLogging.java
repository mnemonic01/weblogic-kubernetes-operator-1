// Copyright 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.io.File;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Simple JUnit test file used for testing Operator.
 *
 * <p>This test is used for testing operator working with Elastic Stack
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItWLSElasticLogging extends BaseTest {
  private static final String elasticStackYamlLoc =
      "kubernetes/samples/scripts/elasticsearch-and-kibana/elasticsearch_and_kibana.yaml";
  private static Operator operator;
  private static Domain domain;
  private static String k8sExecCmdPrefix;
  private static String elasticSearchURL;
  private static Map<String, Object> testVarMap;
  private static String loggingExpArchiveLoc;

  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes. It installs Elastic Stack, verifies Elastic
   * Stack is ready to use, creates an operator and a Weblogic domain
   *
   * @throws Exception exception
   */
  @BeforeClass
  public static void staticPrepare() throws Exception {
    if (!QUICKTEST) {
      // initialize test properties and create the directories
      initialize(APP_PROPS_FILE);

      // Install Elastic Stack
      StringBuffer cmd =
          new StringBuffer("kubectl apply -f ")
              .append(getProjectRoot())
              .append("/")
              .append(elasticStackYamlLoc);
      logger.info("Command to Install Elastic Stack: " + cmd.toString());
      TestUtils.exec(cmd.toString());

      // Create operator-elk
      if (operator == null) {
        logger.info("Creating Operator & waiting for the script to complete execution");
        operator = TestUtils.createOperator(OPERATOR1_ELK_YAML, "2/2");
      }

      // create domain
      if (domain == null) {
        logger.info("Creating WLS Domain & waiting for the script to complete execution");
        domain = TestUtils.createDomain(DOMAINONPV_LOGGINGEXPORTER_YAML);
        domain.verifyDomainCreated();
      }

      // Get Elasticsearch host and port from yaml file and build Elasticsearch URL
      testVarMap = TestUtils.loadYaml(OPERATOR1_ELK_YAML);
      String operatorPodName = operator.getOperatorPodName();
      StringBuffer elasticSearchUrlBuff =
          new StringBuffer("http://")
              .append(testVarMap.get("elasticSearchHost"))
              .append(":")
              .append(testVarMap.get("elasticSearchPort"));
      elasticSearchURL = elasticSearchUrlBuff.toString();
      Assume.assumeFalse(
          "Got null when building Elasticsearch URL", elasticSearchURL.contains("null"));

      // Create the prefix of k8s exec command
      StringBuffer k8sExecCmdPrefixBuff =
          new StringBuffer("kubectl exec -it ")
              .append(operatorPodName)
              .append(" -n ")
              .append(operator.getOperatorNamespace())
              .append(" -- /bin/bash -c ")
              .append("'curl ")
              .append(elasticSearchURL);
      k8sExecCmdPrefix = k8sExecCmdPrefixBuff.toString();

      // Verify that Elastic Stack is ready to use
      verifyElasticStackReady();
    }
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories and uninstall Elastic Stack.
   *
   * @throws Exception exception
   */
  @AfterClass
  public static void staticUnPrepare() throws Exception {
    if (!QUICKTEST) {
      logger.info("+++++++++++++++++++++++++++++++++---------------------------------+");
      logger.info("BEGIN");
      logger.info("Run once, release cluster lease");

      // Uninstall Elastic Stack
      StringBuffer cmd =
          new StringBuffer("kubectl delete -f ")
              .append(getProjectRoot())
              .append("/")
              .append(elasticStackYamlLoc);
      logger.info("Command to uninstall Elastic Stack: " + cmd.toString());
      //TestUtils.exec(cmd.toString());

      tearDown(new Object() {}.getClass().getEnclosingClass().getSimpleName());

      logger.info("SUCCESS");
    }
  }

  private static void verifyElasticStackReady() throws Exception {
    // Get Logstash info
    String healthStatus = execElasticStackStatusCheck("*logstash*", "$1");
    String indexStatus = execElasticStackStatusCheck("*logstash*", "$2");
    String indexName = execElasticStackStatusCheck("*logstash*", "$3");

    Assume.assumeNotNull(healthStatus);
    Assume.assumeNotNull(indexStatus);
    Assume.assumeNotNull(indexName);
    // Verify that the health status of Logstash
    Assume.assumeTrue(
        "Logstash is not ready!",
        healthStatus.equalsIgnoreCase("yellow") || 
        healthStatus.equalsIgnoreCase("green"));
    // Verify that the index is open for use
    Assume.assumeTrue("Logstash index is not open!", 
                      indexStatus.equalsIgnoreCase("open"));
    // Add the index name to a Map
    testVarMap.put("indexName", indexName);

    // Get Kibana info
    healthStatus = execElasticStackStatusCheck("*kibana*", "$1");
    indexStatus = execElasticStackStatusCheck("*kibana*", "$2");
    indexName = execElasticStackStatusCheck("*kibana*", "$3");
    
    Assume.assumeNotNull(healthStatus);
    Assume.assumeNotNull(indexStatus);
    Assume.assumeNotNull(indexName);

    //There are multiple indexes from Kibana 6.8.0
    String[] kibanahealthStatusArr = 
      healthStatus.split(System.getProperty("line.separator"));
    String[] kibanaindexStatusArr = 
      indexStatus.split(System.getProperty("line.separator"));
    String[] kibanaindexNameArr = 
      indexName.split(System.getProperty("line.separator"));
    
    for(int i = 0; i < kibanaindexStatusArr.length; i++) {
      logger.info("Health status of " + kibanaindexNameArr[i] + 
                  "is:" + kibanahealthStatusArr[i]);
      logger.info("Index status of " + kibanaindexNameArr[i] + 
                  "is:" + kibanaindexStatusArr[i]);
      // Verify that the health status of Kibana
      Assume.assumeTrue(
          "Kibana is not ready!",
          kibanahealthStatusArr[i].trim().equalsIgnoreCase("yellow") || 
          kibanahealthStatusArr[i].trim().equalsIgnoreCase("green"));
      // Verify that the index is open for use
      Assume.assumeTrue("Kibana index is not open!", 
                        kibanaindexStatusArr[i].trim().equalsIgnoreCase("open"));
    }
    
    logger.info("ELK Stack is up and running and ready to use!");
  }

  private static String execElasticStackStatusCheck(String indexName, String varLoc)
      throws Exception {
    StringBuffer k8sExecCmdPrefixBuff = new StringBuffer(k8sExecCmdPrefix);
    String cmd =
        k8sExecCmdPrefixBuff
            .append("/_cat/indices/")
            .append(indexName)
            .append(" | awk '\\''{ print ")
            .append(varLoc)
            .append(" }'\\'")
            .toString();
    logger.info("Command to exec Elastic Stack status check: " + cmd);
    ExecResult result = TestUtils.exec(cmd);
    logger.info("Results: " + result.stdout());

    return result.stdout();
  }
 
  /**
   * Use Elasticsearch Search APIs to query Weblogic log info. Verify that log hits for Weblogic
   * servers are not empty
   *
   * @throws Exception exception
   */
  @Test
  public void testWLSLoggingExporter() throws Exception {
    Assume.assumeFalse(QUICKTEST);
    String testMethodName = new Object() {}.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);

    logger.info("================Calling testWLSLoggingExporter");
    
    Map<String, Object> domainMap = domain.getDomainMap();
    String domainUid = domain.getDomainUid();
    String domainNS = domainMap.get("namespace").toString();
    String adminServerName = (String) domainMap.get("adminServerName");
    String adminServerPodName = domainUid + "-" + adminServerName;
    String managedServerNameBase = domainMap.get("managedServerNameBase").toString();
    String managedServerPodNameBase = domainUid + "-" + managedServerNameBase;
    int initialManagedServerReplicas =
        ((Integer) domainMap.get("initialManagedServerReplicas")).intValue();
    
    loggingExpArchiveLoc = "/scratch/huizhao/wls_logging_exporter/";
    String resourceDir = BaseTest.getProjectRoot() + "/integration-tests/src/test/resources/";
    String testResourceDir = resourceDir + "loggingexporter/";

    //Copy test files to admin pod
    TestUtils.kubectlcp(
        loggingExpArchiveLoc + "weblogic-logging-exporter-0.1.jar",
        "/shared/domains/domainonpvwlst/lib/weblogic-logging-exporter-0.1.jar",
        adminServerPodName,
        domainNS);

    TestUtils.kubectlcp(
        loggingExpArchiveLoc + "snakeyaml-1.23.jar",
        "/shared/domains/domainonpvwlst/lib/snakeyaml-1.23.jar",
        adminServerPodName,
        domainNS);

    TestUtils.kubectlcp(
        testResourceDir + "WebLogicLoggingExporter.yaml",
        "/shared/domains/domainonpvwlst/config/WebLogicLoggingExporter.yaml",
        adminServerPodName,
        domainNS);
    
    //Copy test files to all managed server pods
    for (int i = 1; i <= initialManagedServerReplicas; i++) {
      logger.info(
          "Stopping managed pod("
              + domainUid
              + "-"
              + managedServerNameBase
              + i
              + ")");
      
      TestUtils.kubectlcp(
          loggingExpArchiveLoc + "weblogic-logging-exporter-0.1.jar",
          "/shared/domains/domainonpvwlst/lib/weblogic-logging-exporter-0.1.jar",
          managedServerPodNameBase + i,
          domainNS);

      TestUtils.kubectlcp(
          loggingExpArchiveLoc + "snakeyaml-1.23.jar",
          "/shared/domains/domainonpvwlst/lib/snakeyaml-1.23.jar",
          managedServerPodNameBase + i,
          domainNS);

      TestUtils.kubectlcp(
          testResourceDir + "WebLogicLoggingExporter.yaml",
          "/shared/domains/domainonpvwlst/config/WebLogicLoggingExporter.yaml",
          managedServerPodNameBase + i,
          domainNS);
    }

    domain.shutdownUsingServerStartPolicy();
    domain.restartUsingServerStartPolicy();
    
    logger.info("SUCCESS - " + testMethodName);
  }

  private void verifySearchResults(String queryCriteria, String regex, boolean checkCount)
      throws Exception {
    String results = execElasticStackQuery(queryCriteria);

    int count = -1;
    int failedCount = -1;
    String hits = "";
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(results);
    if (matcher.find()) {
      count = Integer.parseInt(matcher.group(1));
      if (checkCount) {
        failedCount = Integer.parseInt(matcher.group(2));
      } else {
        hits = matcher.group(2);
      }
    }

    Assume.assumeTrue("Total count of logs should be more than 0!", count > 0);
    logger.info("Total count of logs: " + count);
    if (checkCount) {
      Assume.assumeTrue("Total failed count should be 0!", failedCount == 0);
      logger.info("Total failed count: " + failedCount);
    } else {
      Assume.assumeFalse("Total hits of search is empty!", hits.isEmpty());
    }
  }

  private String execElasticStackQuery(String queryCriteria) throws Exception {
    StringBuffer k8sExecCmdPrefixBuff = new StringBuffer(k8sExecCmdPrefix);
    int offset = k8sExecCmdPrefixBuff.indexOf("http");
    k8sExecCmdPrefixBuff.insert(offset, " -X GET ");
    String indexName = (String) testVarMap.get("indexName");
    String cmd =
        k8sExecCmdPrefixBuff
            .append("/")
            .append(indexName)
            .append(queryCriteria)
            .append("'")
            .toString();
    logger.info("Command to search: " + cmd);
    ExecResult result = TestUtils.exec(cmd);

    return result.stdout();
  }
  
  private void gitCloneWLSLoggingExporter() throws Exception {
    
    if (!loggingExpArchiveLoc.isEmpty()) {
      StringBuffer removeAndClone = new StringBuffer();
      logger.info(
          "Checking if directory "
              + loggingExpArchiveLoc
              + " exists "
              + new File(loggingExpArchiveLoc).exists());
      if (new File(loggingExpArchiveLoc).exists()) {
        removeAndClone
            .append("rm -rf ")
            .append(loggingExpArchiveLoc);
      }
      // git clone docker-images project
      removeAndClone
          .append(" git clone https://github.com/oracle/docker-images.git ")
          .append(BaseTest.getResultDir())
          .append("/docker-images");
      logger.info("Executing cmd " + removeAndClone);
      TestUtils.exec(removeAndClone.toString());
    }
  }
}
