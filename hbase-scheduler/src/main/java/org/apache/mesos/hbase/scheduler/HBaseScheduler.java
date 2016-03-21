package org.apache.mesos.hbase.scheduler;

import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.Credential;
import org.apache.mesos.Protos.Environment;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.FrameworkInfo;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskState;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Protos.Value;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.hbase.state.AcquisitionPhase;
import org.apache.mesos.hbase.state.LiveState;
import org.apache.mesos.hbase.state.PersistenceException;
import org.apache.mesos.hbase.state.IPersistentStateStore;
import org.apache.mesos.hbase.util.DnsResolver;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import org.apache.mesos.hbase.config.HBaseFrameworkConfig;
import org.apache.mesos.hbase.util.HBaseConstants;
import org.apache.mesos.hbase.util.HdfsConfFileUrlJsonFinder;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * HBase Mesos Framework Scheduler class implementation.
 * TODO: add start of https://wiki.apache.org/hadoop/Hbase/Stargate
 */
public class HBaseScheduler implements org.apache.mesos.Scheduler, Runnable {
  // TODO (elingg) remove as much logic as possible from Scheduler to clean up code
  private final Log log = LogFactory.getLog(HBaseScheduler.class);

  private static final int SECONDS_FROM_MILLIS = 1000;

  private final HBaseFrameworkConfig hbaseFrameworkConfig;
  private final LiveState liveState;
  private final IPersistentStateStore persistenceStore;
  private final DnsResolver dnsResolver;

  private MasterInfo masterInfo;
  private ObjectMapper mapper = new ObjectMapper();

  @Inject
  public HBaseScheduler(HBaseFrameworkConfig hbaseFrameworkConfig,
      LiveState liveState, IPersistentStateStore persistenceStore) {

    this.hbaseFrameworkConfig = hbaseFrameworkConfig;
    this.liveState = liveState;
    this.persistenceStore = persistenceStore;
    this.dnsResolver = new DnsResolver(this, hbaseFrameworkConfig);
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    log.info("Scheduler driver disconnected");
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    log.error("Scheduler driver error: " + message);
    // Currently, it's pretty hard to disambiguate this error from other causes of framework errors.
    // Watch MESOS-2522 which will add a reason field for framework errors to help with this.
    // For now the frameworkId is removed for all messages.
    boolean removeFrameworkId = message.contains("re-register");
    suicide(removeFrameworkId);
  }

  /**
    * Exits the JVM process, optionally deleting Marathon's FrameworkID
    * from the backing persistence store.
    *
    * If `removeFrameworkId` is set, the next Marathon process elected
    * leader will fail to find a stored FrameworkID and invoke `register`
    * instead of `reregister`.  This is important because on certain kinds
    * of framework errors (such as exceeding the framework failover timeout),
    * the scheduler may never re-register with the saved FrameworkID until
    * the leading Mesos master process is killed.
    */
  private void suicide(Boolean removeFrameworkId) {
    if (removeFrameworkId)
    {
      persistenceStore.setFrameworkId(null);
      System.exit(9);
    }
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorID, SlaveID slaveID,
      int status) {
    log.info("Executor lost: executorId=" + executorID.getValue() + " slaveId="
        + slaveID.getValue() + " status=" + status);
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorID, SlaveID slaveID,
      byte[] data) {
    log.info("Framework message: executorId=" + executorID.getValue() + " slaveId="
        + slaveID.getValue() + " data='" + Arrays.toString(data) + "'");
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    log.info("Offer rescinded: offerId=" + offerId.getValue());
  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {

    try {
      persistenceStore.setFrameworkId(frameworkId);
    } catch (PersistenceException e) {
      // these are zk exceptions... we are unable to maintain state.
      final String msg = "Error setting framework id in persistent state";
      log.error(msg, e);
      throw new SchedulerException(msg, e);
    }
    this.masterInfo = masterInfo;
    log.info("Registered framework frameworkId=" + frameworkId.getValue());
    // reconcile tasks upon registration
    reconcileTasks(driver);
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    this.masterInfo = masterInfo;
    log.info("Reregistered framework: starting task reconciliation");
    // reconcile tasks upon reregistration
    reconcileTasks(driver);
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    log.info(String.format(
        "Received status update for taskId=%s state=%s message='%s' stagingTasks.size=%d",
        status.getTaskId().getValue(),
        status.getState().toString(),
        status.getMessage(),
        liveState.getStagingTasksSize()));

    if (!isStagingState(status)) {
      liveState.removeStagingTask(status.getTaskId());
    }

    if (isTerminalState(status)) {
      liveState.removeRunningTask(status.getTaskId());
      persistenceStore.removeTaskId(status.getTaskId().getValue());
      // Correct the phase when a task dies after the reconcile period is over
      if (!liveState.getCurrentAcquisitionPhase().equals(AcquisitionPhase.RECONCILING_TASKS)) {
        correctCurrentPhase();
      }
    } else if (isRunningState(status)) {
      liveState.updateTaskForStatus(status);

      log.info(String.format("Current Acquisition Phase: %s", liveState
          .getCurrentAcquisitionPhase().toString()));

      switch (liveState.getCurrentAcquisitionPhase()) {
        case RECONCILING_TASKS:
          break;
        case START_MASTER_NODES:
          if (liveState.getMasterNodeSize() == HBaseConstants.TOTAL_MASTER_NODES)
          {
            // TODO (elingg) move the reload to correctCurrentPhase and make it idempotent
            reloadConfigsOnAllRunningTasks(driver);
            correctCurrentPhase();
          }
          break;
        // TODO (elingg) add a configurable number of data nodes
        case SLAVE_NODES:
          reloadConfigsOnAllRunningTasks(driver); // all nodes need fetch
                                                  // HBaseConstants.REGION_SERVERS_FILENAME
          break;
      }
    } else {
      log.warn(String.format("Don't know how to handle state=%s for taskId=%s",
          status.getState(), status.getTaskId().getValue()));
    }
  }

  @Override
  public void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    log.info(String.format("Received %d offers", offers.size()));

    // TODO (elingg) within each phase, accept offers based on the number of nodes you need
    boolean acceptedOffer = false;
    for (Offer offer : offers) {
      if (acceptedOffer) {
        driver.declineOffer(offer.getId());
      } else {
        switch (liveState.getCurrentAcquisitionPhase()) {
          case RECONCILING_TASKS:
            log.info("Declining offers while reconciling tasks");
            driver.declineOffer(offer.getId());
            break;
          case START_MASTER_NODES:
            if (tryToLaunchMasterNode(driver, offer)) {
              acceptedOffer = true;
            } else {
              driver.declineOffer(offer.getId());
            }
            break;
          case SLAVE_NODES:
            if (tryToLaunchSlaveNode(driver, offer)) {
              acceptedOffer = true;
            } else {
              driver.declineOffer(offer.getId());
            }
            break;
        }
      }
    }
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    log.info("Slave lost slaveId=" + slaveId.getValue());
  }

  @Override
  public void run() {
    FrameworkInfo.Builder frameworkInfo = FrameworkInfo.newBuilder()
        .setName(hbaseFrameworkConfig.getFrameworkName())
        .setFailoverTimeout(hbaseFrameworkConfig.getFailoverTimeout())
        .setUser(hbaseFrameworkConfig.getHbaseUser())
        .setRole(hbaseFrameworkConfig.getHbaseRole())
        .setCheckpoint(true);

    try {
      FrameworkID frameworkID = persistenceStore.getFrameworkId();
      if (frameworkID != null) {
        frameworkInfo.setId(frameworkID);
      }
    } catch (PersistenceException e) {
      final String msg = "Error recovering framework id";
      log.error(msg, e);
      throw new SchedulerException(msg, e);
    }

    registerFramework(this, frameworkInfo.build(), hbaseFrameworkConfig.getMesosMasterUri());
  }

  private void registerFramework(HBaseScheduler sched, FrameworkInfo fInfo, String masterUri) {
    Credential cred = getCredential();

    if (cred != null) {
      log.info("Registering with credentials.");
      new MesosSchedulerDriver(sched, fInfo, masterUri, cred).run();
    } else {
      log.info("Registering without authentication");
      new MesosSchedulerDriver(sched, fInfo, masterUri).run();
    }
  }

  private Credential getCredential() {
    if (hbaseFrameworkConfig.cramCredentialsEnabled()) {
      try {
        Credential.Builder credentialBuilder = Credential.newBuilder()
            .setPrincipal(hbaseFrameworkConfig.getPrincipal())
            .setSecret(ByteString.copyFrom(hbaseFrameworkConfig.getSecret().getBytes("UTF-8")));

        return credentialBuilder.build();

      } catch (UnsupportedEncodingException ex) {
        log.error("Failed to encode secret when creating Credential.");
      }
    }

    return null;
  }

  private boolean launchNode(SchedulerDriver driver, Offer offer,
      String nodeName, String taskType, String executorName) {
    // nodeName is the type of executor to launch
    // executorName is to distinguish different types of nodes
    // taskType is the type of task in mesos to launch on the node
    // taskName is a name chosen to identify the task in mesos and mesos-dns (if used)
    log.info(String.format("Launching node of type %s with task %s", nodeName, taskType));
    String taskIdName = String.format("%s.%s.%d", nodeName, executorName,
        System.currentTimeMillis());
    List<Resource> resources = getExecutorResources();
    ExecutorInfo executorInfo = createExecutor(taskIdName, taskType, nodeName, executorName,
        resources);

    List<Resource> taskResources = getTaskResources(taskType);
    String taskName = getNextTaskName(taskType);
    TaskID taskId = TaskID.newBuilder()
        .setValue(String.format("task.%s.%s", taskType, taskIdName))
        .build();
    TaskInfo task = TaskInfo.newBuilder()
        .setExecutor(executorInfo)
        .setName(taskName)
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId())
        .addAllResources(taskResources)
        .setData(ByteString.copyFromUtf8(
            getCommand(taskType)))
        .build();

    liveState.addStagingTask(task.getTaskId());
    persistenceStore.addHBaseNode(taskId, offer.getHostname(), taskType, taskName);

    driver.launchTasks(Arrays.asList(offer.getId()), Arrays.asList(task));
    return true;
  }

  private String getCommand(String taskType){
    return String.format("bin/hbase-mesos-%s", taskType);
  }

  private String getNextTaskName(String taskType) {

    if (taskType.equals(HBaseConstants.MASTER_NODE_ID)) {
      Collection<String> masterNodeTaskNames = persistenceStore.getPrimaryNodeTaskNames().values();
      for (int i = 1; i <= HBaseConstants.TOTAL_MASTER_NODES; i++) {
        if (!masterNodeTaskNames.contains(HBaseConstants.MASTER_NODE_ID + i)) {
          return HBaseConstants.MASTER_NODE_ID + i;
        }
      }
      String errorStr = "Cluster is in inconsistent state. " +
          "Trying to launch more masternodes, but they are all already running.";
      log.error(errorStr);
      throw new SchedulerException(errorStr);
    }
    return taskType;
  }

  private ExecutorInfo createExecutor(String taskIdName, String taskType, String nodeName,
      String executorName,
      List<Resource> resources) {
    int confServerPort = hbaseFrameworkConfig.getConfigServerPort();

    String cmd = "export JAVA_HOME=$MESOS_DIRECTORY/" + hbaseFrameworkConfig.getJreVersion()
        + " && env ; cd hbase-mesos-* && "
        + "exec `if [ -z \"$JAVA_HOME\" ]; then echo java; "
        + "else echo $JAVA_HOME/bin/java; fi` "
        + "$HADOOP_OPTS "
        + "$EXECUTOR_OPTS "
        + "-cp \"hbase-executor-uber.jar\" org.apache.mesos.hbase.executor." + executorName;

    return ExecutorInfo
        .newBuilder()
        .setName(nodeName + " executor")
        .setExecutorId(ExecutorID.newBuilder().setValue("executor." + taskIdName).build())
        .addAllResources(resources)
        .setCommand(CommandInfo
            .newBuilder()
            .addAllUris(Arrays.asList(
                CommandInfo.URI
                    .newBuilder()
                    .setValue(String.format("http://%s:%d/%s",
                        hbaseFrameworkConfig.getFrameworkHostAddress(),
                        confServerPort,
                        HBaseConstants.HBASE_BINARY_FILE_NAME))
                    .build(),
                CommandInfo.URI
                    .newBuilder()
                    .setValue(String.format("http://%s:%d/%s",
                        hbaseFrameworkConfig.getFrameworkHostAddress(),
                        confServerPort,
                        HBaseConstants.REGION_SERVERS_FILENAME))
                    .build(),
                CommandInfo.URI
                    .newBuilder()
                    .setValue(String.format("http://%s:%d/%s",
                        hbaseFrameworkConfig.getFrameworkHostAddress(),
                        confServerPort,
                        HBaseConstants.HBASE_CONFIG_FILE_NAME))
                    .build(),
                CommandInfo.URI
                    .newBuilder()
                    .setValue(getHdfsFileUrl())
                    .build(),
                CommandInfo.URI
                    .newBuilder()
                    .setValue(hbaseFrameworkConfig.getJreUrl())
                    .build()))
            .setEnvironment(Environment
                .newBuilder()
                .addAllVariables(Arrays.asList(Environment.Variable.newBuilder()
                    .setName("LD_LIBRARY_PATH")
                    .setValue(hbaseFrameworkConfig.getLdLibraryPath()).build(),
                    Environment.Variable.newBuilder()
                        .setName("HBASE_OPTS")
                        .setValue(getJvmOpts(taskType)).build(),
                    Environment.Variable
                        .newBuilder()
                        .setName("HBASE_HEAPSIZE")
                        .setValue(getHeapSizeConfig(taskType))
                        .build())))
            .setValue(cmd).build())
        .build();
  }

  private String getJvmOpts(String taskType)
  {
    if (HBaseConstants.MASTER_NODE_ID.equals(taskType))
      return hbaseFrameworkConfig.getJvmOpts();
    else if (HBaseConstants.SLAVE_NODE_ID.equals(taskType))
      return hbaseFrameworkConfig.getJvmOpts();
    else
      return hbaseFrameworkConfig.getJvmOpts();
  }

  private String getHeapSizeConfig(String taskType)
  {
    int heapSize = hbaseFrameworkConfig.getHadoopHeapSize();
    if (null != taskType)
      switch (taskType) {
        case HBaseConstants.MASTER_NODE_ID:
          heapSize = hbaseFrameworkConfig.getMasterNodeHeapSize();
          break;
        case HBaseConstants.SLAVE_NODE_ID:
          heapSize = hbaseFrameworkConfig.getSlaveNodeHeapSize();
          break;
      }

    return String.format("%dm", heapSize);
  }

  private List<Resource> getExecutorResources() {
    return Arrays.asList(Resource.newBuilder()
        .setName("cpus")
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder()
            .setValue(hbaseFrameworkConfig.getExecutorCpus()).build())
        .setRole(hbaseFrameworkConfig.getHbaseRole())
        .build(),
        Resource
            .newBuilder()
            .setName("mem")
            .setType(Value.Type.SCALAR)
            .setScalar(Value.Scalar
                .newBuilder()
                .setValue(hbaseFrameworkConfig.getExecutorHeap()
                    * hbaseFrameworkConfig.getJvmOverhead()).build())
            .setRole(hbaseFrameworkConfig.getHbaseRole())
            .build());
  }

  private List<Resource> getTaskResources(String taskName) {
    return Arrays.asList(Resource.newBuilder()
        .setName("cpus")
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder()
            .setValue(hbaseFrameworkConfig.getTaskCpus(taskName)).build())
        .setRole(hbaseFrameworkConfig.getHbaseRole())
        .build(),
        Resource.newBuilder()
            .setName("mem")
            .setType(Value.Type.SCALAR)
            .setScalar(Value.Scalar.newBuilder()
                .setValue(hbaseFrameworkConfig.getTaskHeapSize(taskName) *
                    hbaseFrameworkConfig.getJvmOverhead()).build())
            .setRole(hbaseFrameworkConfig.getHbaseRole())
            .build());
  }

  private boolean acceptOffer(Offer offer, String nodeType, double cpu, int memory)
  {
    if (offerNotEnoughCpu(offer, cpu))
    {
      log.info(nodeType + " node offer does not have enough cpu.\n Required " + cpu
          + ". (ConfNodeCpus)");
      return false;
    }
    else if (offerNotEnoughMemory(offer, memory))
    {
      double requiredMem = (memory * hbaseFrameworkConfig.getJvmOverhead())
          + (hbaseFrameworkConfig.getExecutorHeap() * hbaseFrameworkConfig.getJvmOverhead());
      String memLog = "Required " + requiredMem + " mem (" + nodeType
          + "NodeHeapSize * jvmOverhead) + (executorHeap * jvmOverhead)";
      log.info(nodeType + " node offer does not have enough memory.\n" + memLog);
      return false;
    } else {
      return true;
    }
  }

  private boolean tryToLaunchMasterNode(SchedulerDriver driver, Offer offer)
  {
    if (!acceptOffer(offer, "master", hbaseFrameworkConfig.getMasterNodeCpus(),
        hbaseFrameworkConfig.getMasterNodeHeapSize()))
      return false;

    boolean launch = false;
    List<String> deadMasterNodes = persistenceStore.getDeadMasterNodes();

    if (deadMasterNodes.isEmpty()) {
      if (persistenceStore.getPrimaryNodes().size() == HBaseConstants.TOTAL_MASTER_NODES) {
        log.info(String.format("Already running %s masters", HBaseConstants.TOTAL_MASTER_NODES));
      } else if (persistenceStore.masterNodeRunningOnSlave(offer.getHostname())) {
        log.info(String.format("Already running masternode on %s", offer.getHostname()));
      } else if (persistenceStore.slaveNodeRunningOnSlave(offer.getHostname())) {
        log.info(String.format("Cannot colocate masternode and slavenode on %s", offer.getHostname()));
      } else {
        launch = true;
      }
    } else if (deadMasterNodes.contains(offer.getHostname())) {
      launch = true;
    }
    if (launch) {
      return launchNode(driver,
          offer,
          HBaseConstants.MASTER_NODE_ID,
          HBaseConstants.MASTER_NODE_ID,
          HBaseConstants.NODE_EXECUTOR_ID);
    }
    return false;
  }

  private boolean tryToLaunchSlaveNode(SchedulerDriver driver, Offer offer) {
    if (!acceptOffer(offer, "slave", hbaseFrameworkConfig.getMasterNodeCpus(),
        hbaseFrameworkConfig.getMasterNodeHeapSize()))
      return false;

    boolean launch = false;
    List<String> deadDataNodes = persistenceStore.getDeadDataNodes();
    // TODO (elingg) Relax this constraint to only wait for DN's when the number of DN's is small
    // What number of DN's should we try to recover or should we remove this constraint
    // entirely?
    if (deadDataNodes.isEmpty()) {
      if (persistenceStore.slaveNodeRunningOnSlave(offer.getHostname()) || persistenceStore.masterNodeRunningOnSlave(offer.getHostname())){
        log.info(String.format("Already running hbase task on %s", offer.getHostname()));
      } else {
        launch = true;
      }
    } else if (deadDataNodes.contains(offer.getHostname())) {
      launch = true;
    }
    if (launch) {
      return launchNode(driver,
          offer,
          HBaseConstants.SLAVE_NODE_ID,
          HBaseConstants.SLAVE_NODE_ID,
          HBaseConstants.NODE_EXECUTOR_ID);
    }
    return false;
  }

  public void sendMessageTo(SchedulerDriver driver, TaskID taskId,
      SlaveID slaveID, String message) {
    log.info(String.format("Sending message '%s' to taskId=%s, slaveId=%s", message,
        taskId.getValue(), slaveID.getValue()));
    String postfix = taskId.getValue();
    postfix = postfix.substring(postfix.indexOf('.') + 1, postfix.length());
    postfix = postfix.substring(postfix.indexOf('.') + 1, postfix.length());
    driver.sendFrameworkMessage(
        ExecutorID.newBuilder().setValue("executor." + postfix).build(),
        slaveID,
        message.getBytes(Charset.defaultCharset()));
  }

  private boolean isTerminalState(TaskStatus taskStatus) {
    return taskStatus.getState().equals(TaskState.TASK_FAILED)
        || taskStatus.getState().equals(TaskState.TASK_FINISHED)
        || taskStatus.getState().equals(TaskState.TASK_KILLED)
        || taskStatus.getState().equals(TaskState.TASK_LOST)
        || taskStatus.getState().equals(TaskState.TASK_ERROR);
  }

  private boolean isRunningState(TaskStatus taskStatus) {
    return taskStatus.getState().equals(TaskState.TASK_RUNNING);
  }

  private boolean isStagingState(TaskStatus taskStatus) {
    return taskStatus.getState().equals(TaskState.TASK_STAGING);
  }

  private void reloadConfigsOnAllRunningTasks(SchedulerDriver driver) {
    if (hbaseFrameworkConfig.usingNativeHadoopBinaries()) {
      return;
    }
    for (Protos.TaskStatus taskStatus : liveState.getRunningTasks().values()) {
      sendMessageTo(driver, taskStatus.getTaskId(), taskStatus.getSlaveId(),
          HBaseConstants.RELOAD_CONFIG);
    }
  }

  private void correctCurrentPhase() {
    if (liveState.getMasterNodeSize() < HBaseConstants.TOTAL_MASTER_NODES) {
      liveState.transitionTo(AcquisitionPhase.START_MASTER_NODES);
    } else {
      liveState.transitionTo(AcquisitionPhase.SLAVE_NODES);
    }
  }

  private boolean offerNotEnoughCpu(Offer offer, double cpus) {
    for (Resource offerResource : offer.getResourcesList()) {
      if (offerResource.getName().equals("cpus") &&
          cpus + hbaseFrameworkConfig.getExecutorCpus() > offerResource.getScalar().getValue()) {
        return true;
      }
    }
    return false;
  }

  private boolean offerNotEnoughMemory(Offer offer, int mem) {
    for (Resource offerResource : offer.getResourcesList()) {
      if (offerResource.getName().equals("mem") &&
          (mem * hbaseFrameworkConfig.getJvmOverhead())
              + (hbaseFrameworkConfig.getExecutorHeap() * hbaseFrameworkConfig.getJvmOverhead())
              > offerResource.getScalar().getValue()) {
        return true;
      }
    }
    return false;
  }

  private void reconcileTasks(SchedulerDriver driver) {
    // TODO (elingg) run this method repeatedly with exponential backoff in the case that it takes
    // time for
    // different slaves to reregister upon master failover.
    driver.reconcileTasks(Collections.<Protos.TaskStatus>emptyList());
    Timer timer = new Timer();
    timer.schedule(new ReconcileStateTask(), hbaseFrameworkConfig.getReconciliationTimeout()
        * SECONDS_FROM_MILLIS);
  }

  private String getHdfsFileUrl()
  {
    if (masterInfo == null)
    {
      log.error("Invalid scheduler state - masterInfo is null");
      return getHbaseConfigServerHdfsFileUrl();
    }
    else if (hbaseFrameworkConfig.usingMesosHdfs())
    {
      String masterStateUrl = String.format("http://%s:%d/%s", masterInfo.getHostname(),
          masterInfo.getPort(), "master/state.json");
      try {
        URL url = new URL(masterStateUrl);
        HdfsConfFileUrlJsonFinder finder = new HdfsConfFileUrlJsonFinder(mapper);
        String findedUrl = finder.findUrl(url);
        return findedUrl;
      } catch (IOException e) {
        log.error("", e);
      }
    } else {
      return getHbaseConfigServerHdfsFileUrl();
    }
    return null;
  }

  private String getHbaseConfigServerHdfsFileUrl()
  {
    return String.format("http://%s:%d/%s",
        hbaseFrameworkConfig.getFrameworkHostAddress(),
        hbaseFrameworkConfig.getConfigServerPort(),
        HBaseConstants.HDFS_CONFIG_FILE_NAME);
  }

  private class ReconcileStateTask extends TimerTask {

    @Override
    public void run() {
      log.info("Current persistent state:");
      log.info(String.format("Primary Nodes: %s, %s", persistenceStore.getPrimaryNodes(),
          persistenceStore.getPrimaryNodeTaskNames()));
      log.info(String.format("Slave Nodes: %s", persistenceStore.getRegionNodes()));

      Set<String> taskIds = persistenceStore.getAllTaskIds();
      Set<String> runningTaskIds = liveState.getRunningTasks().keySet();

      for (String taskId : taskIds) {
        if (taskId != null && !runningTaskIds.contains(taskId)) {
          log.info("Removing task id: " + taskId);
          persistenceStore.removeTaskId(taskId);
        }
      }
      correctCurrentPhase();
    }
  }
}
