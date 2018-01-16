package com.terracottatech.qa.angela.agent;

import com.terracottatech.qa.angela.agent.kit.TmsInstall;
import com.terracottatech.qa.angela.common.TerracottaManagementServerInstance;
import com.terracottatech.qa.angela.common.TerracottaManagementServerState;
import com.terracottatech.qa.angela.common.distribution.Distribution;
import org.apache.commons.io.FileUtils;
import org.apache.ignite.Ignite;
import org.apache.ignite.configuration.CollectionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.LogOutputStream;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.ProcessUtil;
import org.zeroturnaround.process.Processes;

import com.terracottatech.qa.angela.agent.kit.KitManager;
import com.terracottatech.qa.angela.agent.kit.TerracottaInstall;
import com.terracottatech.qa.angela.common.TerracottaServerInstance;
import com.terracottatech.qa.angela.common.TerracottaServerState;
import com.terracottatech.qa.angela.common.tcconfig.License;
import com.terracottatech.qa.angela.common.tcconfig.TcConfig;
import com.terracottatech.qa.angela.common.tcconfig.TerracottaServer;
import com.terracottatech.qa.angela.common.topology.InstanceId;
import com.terracottatech.qa.angela.common.topology.Topology;
import com.terracottatech.qa.angela.common.util.FileMetadata;
import com.terracottatech.qa.angela.common.util.JavaLocationResolver;
import com.terracottatech.qa.angela.common.util.OS;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Aurelien Broszniowski
 */

public class AgentController {

  private final static Logger logger = LoggerFactory.getLogger(AgentController.class);

  private final OS os = new OS();
  private final Map<InstanceId, TerracottaInstall> kitsInstalls = new HashMap<>();
  private final Map<InstanceId, TmsInstall> tmsInstalls = new HashMap<>();
  private final Ignite ignite;

  AgentController(Ignite ignite) {
    this.ignite = ignite;
  }

  public void install(InstanceId instanceId, Topology topology, TerracottaServer terracottaServer, boolean offline, License license, int tcConfigIndex) {
    TerracottaInstall terracottaInstall = kitsInstalls.get(instanceId);
    if (terracottaInstall != null) {
      logger.info("Kit for " + terracottaServer + " already installed");
      terracottaInstall.addServer(terracottaServer);
    } else {
      logger.info("Installing kit for " + terracottaServer);
      KitManager kitManager = new KitManager(instanceId, topology.getDistribution(), topology.getKitInstallationPath());
      File kitDir = kitManager.installKit(license, offline);

      logger.info("Installing the tc-configs");
      for (TcConfig tcConfig : topology.getTcConfigs()) {
        tcConfig.updateLogsLocation(kitDir, tcConfigIndex);
        tcConfig.writeTcConfigFile(kitDir);
        logger.info("Tc Config installed config path : {}", tcConfig.getPath());
      }

      kitsInstalls.put(instanceId, new TerracottaInstall(topology, terracottaServer, kitDir));
    }
  }

  public void installTms(InstanceId instanceId, String tmsHostname, Distribution distribution, String kitInstallationPath, License license) {
    TmsInstall tmsInstall = tmsInstalls.get(instanceId);
    if (tmsInstall != null) {
      logger.info("Kit for " + tmsHostname + " already installed");
      tmsInstall.addTerracottaManagementServer();
    } else {
      logger.info("Installing kit for " + tmsHostname);
      KitManager kitManager = new KitManager(instanceId, distribution, kitInstallationPath);
      File kitDir = kitManager.installKit(license, false);

      tmsInstalls.put(instanceId, new TmsInstall(distribution, kitDir));
    }
  }

  public void startTms(final InstanceId instanceId) {
    TerracottaManagementServerInstance serverInstance = tmsInstalls.get(instanceId)
        .getTerracottaManagementServerInstance();
    serverInstance.start();
  }

  public void stopTms(final InstanceId instanceId) {
    TerracottaManagementServerInstance serverInstance = tmsInstalls.get(instanceId)
        .getTerracottaManagementServerInstance();
    serverInstance.stop();
  }

  public TerracottaManagementServerState getTerracottaManagementServerState(final InstanceId instanceId) {
    TmsInstall terracottaInstall = tmsInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return TerracottaManagementServerState.NOT_INSTALLED;
    }
    TerracottaManagementServerInstance serverInstance = terracottaInstall.getTerracottaManagementServerInstance();
    if (serverInstance == null) {
      return TerracottaManagementServerState.NOT_INSTALLED;
    }
    return serverInstance.getTerracottaManagementServerState();
  }

  public void uninstall(InstanceId instanceId, Topology topology, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = kitsInstalls.get(instanceId);
    if (terracottaInstall != null) {
      int installationsCount = terracottaInstall.removeServer(terracottaServer);
      TmsInstall tmsInstall = tmsInstalls.get(instanceId);
      if (installationsCount == 0 && (tmsInstall == null || tmsInstall.getTerracottaManagementServerInstance() == null)) {
        try {
          logger.info("Uninstalling kit for " + topology);
          KitManager kitManager = new KitManager(instanceId, topology.getDistribution(), topology.getKitInstallationPath());
          // TODO : get log files

          kitManager.deleteInstall(terracottaInstall.getInstallLocation());
          kitsInstalls.remove(instanceId);
        } catch (IOException ioe) {
          throw new RuntimeException("Unable to uninstall kit at " + terracottaInstall.getInstallLocation()
              .getAbsolutePath(), ioe);
        }
      } else {
        logger.info("Kit install still in use by {} Terracotta servers", installationsCount + (tmsInstall == null ? 0 : tmsInstall.getTerracottaManagementServerInstance() == null ? 0 : 1));
      }
    } else {
      logger.info("No installed kit for " + topology);
    }
  }


  public void uninstallTms(InstanceId instanceId, Distribution distribution, String kitInstallationPath, String tmsHostname) {
    TmsInstall tmsInstall = tmsInstalls.get(instanceId);
    if (tmsInstall != null) {
      tmsInstall.removeServer();
      int numberOfTerracottaInstances = kitsInstalls.get(instanceId).numberOfTerracottaInstances();
      if (numberOfTerracottaInstances == 0) {
        try {
          logger.info("Uninstalling kit for " + tmsHostname);
          KitManager kitManager = new KitManager(instanceId, distribution, kitInstallationPath);
          // TODO : get log files

          kitManager.deleteInstall(tmsInstall.getInstallLocation());
          kitsInstalls.remove(instanceId);
        } catch (IOException ioe) {
          throw new RuntimeException("Unable to uninstall kit at " + tmsInstall.getInstallLocation()
              .getAbsolutePath(), ioe);
        }
      } else {
        logger.info("Kit install still in use by {} Terracotta servers", numberOfTerracottaInstances);
      }
    } else {
      logger.info("No installed kit for " + tmsHostname);
    }
  }

  public void start(final InstanceId instanceId, final TerracottaServer terracottaServer) {
    TerracottaServerInstance serverInstance = kitsInstalls.get(instanceId)
        .getTerracottaServerInstance(terracottaServer);
    serverInstance.start();
  }

  public void stop(final InstanceId instanceId, final TerracottaServer terracottaServer) {
    TerracottaServerInstance serverInstance = kitsInstalls.get(instanceId)
        .getTerracottaServerInstance(terracottaServer);
    serverInstance.stop();
  }

  public TerracottaServerState getTerracottaServerState(final InstanceId instanceId, final TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = kitsInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return TerracottaServerState.NOT_INSTALLED;
    }
    TerracottaServerInstance serverInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    if (serverInstance == null) {
      return TerracottaServerState.NOT_INSTALLED;
    }
    return serverInstance.getTerracottaServerState();
  }

  public void configureLicense(final InstanceId instanceId, final TerracottaServer terracottaServer, final License license, final TcConfig[] tcConfigs) {
    TerracottaServerInstance serverInstance = kitsInstalls.get(instanceId)
        .getTerracottaServerInstance(terracottaServer);
    serverInstance.configureLicense(instanceId, license, tcConfigs);

  }

  public void destroyClient(InstanceId instanceId, String subNodeName, int pid) {
    try {
      logger.info("killing client '{}' with PID {}", subNodeName, pid);
      PidProcess pidProcess = Processes.newPidProcess(pid);
      ProcessUtil.destroyGracefullyOrForcefullyAndWait(pidProcess, 30, TimeUnit.SECONDS, 10, TimeUnit.SECONDS);

      File subAgentRoot = clientRootDir(instanceId, subNodeName);
      logger.info("cleaning up directory structure '{}' of client {}", subAgentRoot, subNodeName);
      FileUtils.deleteDirectory(subAgentRoot);
    } catch (Exception e) {
      throw new RuntimeException("Error cleaning up client " + subNodeName, e);
    }
  }

  public int spawnClient(InstanceId instanceId, String subNodeName) {
    try {
      JavaLocationResolver javaLocationResolver = new JavaLocationResolver();
      List<String> j8Homes = javaLocationResolver.resolveJava8Location();
      String j8Home = j8Homes.get(0);

      final AtomicBoolean started = new AtomicBoolean(false);
      List<String> cmdLine;
      if (os.isWindows()) {
        cmdLine = Arrays.asList(j8Home + "\\bin\\java.exe", "-classpath", buildClasspath(instanceId, subNodeName), "-Dtc.qa.nodeName=" + subNodeName, "-D" + Agent.ROOT_DIR_SYSPROP_NAME + "=" + Agent.ROOT_DIR, Agent.class
            .getName());
      } else {
        cmdLine = Arrays.asList(j8Home + "/bin/java", "-classpath", buildClasspath(instanceId, subNodeName), "-Dtc.qa.nodeName=" + subNodeName, "-D" + Agent.ROOT_DIR_SYSPROP_NAME + "=" + Agent.ROOT_DIR, Agent.class
            .getName());
      }
      logger.info("Spawning client {}", cmdLine);
      ProcessExecutor processExecutor = new ProcessExecutor().command(cmdLine)
          .redirectOutput(new LogOutputStream() {
            @Override
            protected void processLine(String line) {
              System.out.println(" |" + subNodeName + "| " + line);
              if (line.equals(Agent.AGENT_IS_READY_MARKER_LOG)) {
                started.set(true);
              }
            }
          }).directory(clientRootDir(instanceId, subNodeName));
      StartedProcess startedProcess = processExecutor.start();

      while (!started.get()) {
        Thread.sleep(1000);
      }

      int pid = PidUtil.getPid(startedProcess.getProcess());
      logger.info("Spawned client with PID {}", pid);
      return pid;
    } catch (Exception e) {
      throw new RuntimeException("Error spawning client " + subNodeName, e);
    }
  }

  private static String buildClasspath(InstanceId instanceId, String subNodeName) {
    File subClientDir = new File(clientRootDir(instanceId, subNodeName), "lib");
    String[] cpEntries = subClientDir.list();
    if (cpEntries == null) {
      throw new IllegalStateException("No client to spawn from " + instanceId + " and " + subNodeName);
    }

    StringBuilder sb = new StringBuilder();
    for (String cpentry : cpEntries) {
      sb.append("lib").append(File.separator).append(cpentry).append(File.pathSeparator);
    }

    // if
    //   file:/Users/lorban/.m2/repository/org/slf4j/slf4j-api/1.7.22/slf4j-api-1.7.22.jar!/org/slf4j/Logger.class
    // else
    //   /work/terracotta/irepo/lorban/angela/agent/target/classes/com/terracottatech/qa/angela/agent/Agent.class

    String agentClassPath = Agent.class.getResource('/' + Agent.class.getName().replace('.', '/') + ".class").getPath();

    if (agentClassPath.startsWith("file:")) {
      sb.append(agentClassPath.substring("file:".length(), agentClassPath.lastIndexOf('!')));
    } else {
      sb.append(agentClassPath.substring(0, agentClassPath.lastIndexOf(Agent.class.getName()
          .replace('.', File.separatorChar))));
    }

    return sb.toString();
  }

  public void downloadClient(InstanceId instanceId, String subNodeName) {
    final BlockingQueue<Object> queue = ignite.queue(instanceId + "@file-transfer-queue@" + subNodeName, 100, new CollectionConfiguration());
    try {
      File subClientDir = new File(clientRootDir(instanceId, subNodeName), "lib");
      logger.info("Downloading client '{}' into {}", subNodeName, subClientDir);
      if (!subClientDir.mkdirs()) {
        throw new RuntimeException("Cannot create client directory '" + subClientDir + "' on " + subNodeName);
      }

      while (true) {
        Object read = queue.take();
        if (read.equals(Boolean.TRUE)) {
          logger.info("Downloaded client '{}' into {}", subNodeName, subClientDir);
          break;
        }

        FileMetadata fileMetadata = (FileMetadata)read;
        logger.debug("downloading " + fileMetadata);
        if (!fileMetadata.isDirectory()) {
          long readFileLength = 0L;
          File file = new File(subClientDir + File.separator + fileMetadata.getPathName());
          file.getParentFile().mkdirs();
          try (FileOutputStream fos = new FileOutputStream(file)) {
            while (true) {
              byte[] buffer = (byte[])queue.take();
              fos.write(buffer);
              readFileLength += buffer.length;
              if (readFileLength == fileMetadata.getLength()) {
                break;
              }
              if (readFileLength > fileMetadata.getLength()) {
                throw new RuntimeException("Error creating client classpath on " + subNodeName);
              }
            }
          }
          logger.debug("downloaded " + fileMetadata);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot upload client on " + subNodeName, e);
    }
  }


  private static File clientRootDir(InstanceId instanceId, String subNodeName) {
    return new File(instanceRootDir(instanceId), subNodeName);
  }

  private static File instanceRootDir(InstanceId instanceId) {
    return new File(Agent.ROOT_DIR, instanceId.toString());
  }

  public void cleanup(InstanceId instanceId) {
    logger.info("Cleaning up instance {}", instanceId);
    try {
      FileUtils.deleteDirectory(instanceRootDir(instanceId));
    } catch (IOException ioe) {
      throw new RuntimeException("Error cleaning up instance root directory : " + instanceRootDir(instanceId), ioe);
    }
  }
}
