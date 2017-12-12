// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ShellProcessHandler;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import org.yb.client.YBClient;
import play.libs.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.MASTER;
import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.TSERVER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;


@RunWith(MockitoJUnitRunner.class)
public class UpgradeUniverseTest extends CommissionerBaseTest {
  @InjectMocks
  Commissioner commissioner;

  @InjectMocks
  UpgradeUniverse upgradeUniverse;

  YBClient mockClient;
  Universe defaultUniverse;
  ShellProcessHandler.ShellResponse dummyShellResponse;

  @Before
  public void setUp() {
    super.setUp();
    upgradeUniverse.setUserTaskUUID(UUID.randomUUID());
    Region region = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    AvailabilityZone.create(region, "az-1", "AZ 1", "subnet-1");
    // create default universe
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = "old-version";
    userIntent.accessKeyCode = "demo-access";
    userIntent.regionList = ImmutableList.of(region.uuid);
    userIntent.isMultiAZ = false;
    defaultUniverse = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    Universe.saveDetails(defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, true /* setMasters */));

    // Setup mocks
    mockClient = mock(YBClient.class);
    when(mockYBClient.getClient(any())).thenReturn(mockClient);
    when(mockClient.waitForServer(any(HostAndPort.class), anyLong())).thenReturn(true);
    dummyShellResponse =  new ShellProcessHandler.ShellResponse();
    when(mockNodeManager.nodeCommand(any(), any())).thenReturn(dummyShellResponse);
  }

  private TaskInfo submitTask(UpgradeUniverse.Params taskParams,
                              UpgradeUniverse.UpgradeTaskType taskType) {
    taskParams.universeUUID = defaultUniverse.universeUUID;
    taskParams.taskType = taskType;
    taskParams.expectedUniverseVersion = 2;
    taskParams.sleepAfterMasterRestartMillis = 0;
    taskParams.sleepAfterTServerRestartMillis = 0;

    try {
      UUID taskUUID = commissioner.submit(TaskType.UpgradeUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  List<String> PROPERTY_KEYS = ImmutableList.of("processType", "taskSubType");

  List<TaskType> NON_NODE_TASKS = ImmutableList.of(
      TaskType.LoadBalancerStateChange,
      TaskType.UpdateAndPersistGFlags,
      TaskType.UpdateSoftwareVersion,
      TaskType.UniverseUpdateSucceeded);

  List<TaskType> GFLAGS_UPGRADE_TASK_SEQUENCE = ImmutableList.of(
      TaskType.SetNodeState,
      TaskType.AnsibleClusterServerCtl,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleClusterServerCtl,
      TaskType.SetNodeState,
      TaskType.WaitForServer
  );

  List<TaskType> GFLAGS_ROLLING_UPGRADE_TASK_SEQUENCE = ImmutableList.of(
      TaskType.SetNodeState,
      TaskType.AnsibleClusterServerCtl,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleClusterServerCtl,
      TaskType.SetNodeState
  );

  List<TaskType> SOFTWARE_FULL_UPGRADE_TASK_SEQUENCE = ImmutableList.of(
      TaskType.SetNodeState,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleClusterServerCtl,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleClusterServerCtl,
      TaskType.SetNodeState,
      TaskType.WaitForServer
  );

  List<TaskType> SOFTWARE_ROLLING_UPGRADE_TASK_SEQUENCE = ImmutableList.of(
      TaskType.SetNodeState,
      TaskType.AnsibleClusterServerCtl,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleConfigureServers,
      TaskType.AnsibleClusterServerCtl,
      TaskType.SetNodeState
  );

  private int assertSoftwareUpgradeSequence(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                            int startPosition, boolean isRollingUpgrade) {

    int position = startPosition;
    if (isRollingUpgrade) {
      for (int nodeIdx = 1; nodeIdx <= 3; nodeIdx++) {
        String nodeName = String.format("host-n%d", nodeIdx);
        for (int j = 0; j < SOFTWARE_ROLLING_UPGRADE_TASK_SEQUENCE.size(); j++) {
          Map<String, Object> assertValues = new HashMap();
          List<TaskInfo> tasks = subTasksByPosition.get(position);
          TaskType taskType = tasks.get(0).getTaskType();
          assertEquals(1, tasks.size());
          assertEquals(SOFTWARE_ROLLING_UPGRADE_TASK_SEQUENCE.get(j), taskType);
          if (!NON_NODE_TASKS.contains(taskType)) {
            assertValues.putAll(ImmutableMap.of(
                "nodeName", nodeName, "nodeCount", 1
            ));

            if (taskType.equals(TaskType.AnsibleConfigureServers)) {
              String version = "new-version";
              assertValues.putAll(ImmutableMap.of("ybSoftwareVersion", version ));
            }
            assertNodeSubTask(tasks, assertValues);
          }
          position++;
        }
      }

    } else {
      for (int j = 0; j < SOFTWARE_FULL_UPGRADE_TASK_SEQUENCE.size(); j++) {
        Map<String, Object> assertValues = new HashMap();
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        TaskType taskType = assertTaskType(tasks, SOFTWARE_FULL_UPGRADE_TASK_SEQUENCE.get(j));

        if (NON_NODE_TASKS.contains(taskType)) {
          assertEquals(1, tasks.size());
        } else {
          assertValues.putAll(ImmutableMap.of(
              "nodeNames", (Object) ImmutableList.of("host-n1", "host-n2", "host-n3"),
              "nodeCount", 3
          ));
          if (taskType.equals(TaskType.AnsibleConfigureServers)) {
            String version = "new-version";
            assertValues.putAll(ImmutableMap.of("ybSoftwareVersion", version));
          }
          assertEquals(3, tasks.size());
          assertNodeSubTask(tasks, assertValues);
        }
        position++;
      }
    }
    return position;
  }

  private int assertGFlagsUpgradeSequence(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                          UniverseDefinitionTaskBase.ServerType serverType,
                                          int startPosition, boolean isRollingUpgrade) {
    int position = startPosition;
    if (isRollingUpgrade) {
      for (int nodeIdx = 1; nodeIdx <= 3; nodeIdx++) {
        String nodeName = String.format("host-n%d", nodeIdx);
        for (int j = 0; j < GFLAGS_ROLLING_UPGRADE_TASK_SEQUENCE.size(); j++) {
          Map<String, Object> assertValues = new HashMap();
          List<TaskInfo> tasks = subTasksByPosition.get(position);
          TaskType taskType = tasks.get(0).getTaskType();
          assertEquals(1, tasks.size());
          assertEquals(GFLAGS_ROLLING_UPGRADE_TASK_SEQUENCE.get(j), taskType);
          if (!NON_NODE_TASKS.contains(taskType)) {
            assertValues.putAll(ImmutableMap.of(
                "nodeName", nodeName, "nodeCount", 1
            ));

            if (taskType.equals(TaskType.AnsibleConfigureServers)) {
              JsonNode gflagValue = serverType.equals(MASTER) ?
                  Json.parse("{\"master-flag\":\"m1\"}") :
                  Json.parse("{\"tserver-flag\":\"t1\"}");
              assertValues.putAll(ImmutableMap.of(
                  "gflags", gflagValue, "processType", serverType.toString()
              ));
            }
            assertNodeSubTask(tasks, assertValues);
          }
          position++;
        }
      }
    }
    else {
      for (int j = 0; j < GFLAGS_UPGRADE_TASK_SEQUENCE.size(); j++) {
        Map<String, Object> assertValues = new HashMap();
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        TaskType taskType = assertTaskType(tasks, GFLAGS_UPGRADE_TASK_SEQUENCE.get(j));

        if (NON_NODE_TASKS.contains(taskType)) {
          assertEquals(1, tasks.size());
        } else {
          assertValues.putAll(ImmutableMap.of(
              "nodeNames", (Object) ImmutableList.of("host-n1", "host-n2", "host-n3"),
              "nodeCount", 3
          ));
          if (taskType.equals(TaskType.AnsibleConfigureServers)) {
            JsonNode gflagValue = serverType.equals(MASTER) ?
                Json.parse("{\"master-flag\":\"m1\"}"):
                Json.parse("{\"tserver-flag\":\"t1\"}");
            assertValues.putAll(ImmutableMap.of("gflags",  gflagValue, "processType", serverType.toString()));
          }
          assertEquals(3, tasks.size());
          assertNodeSubTask(tasks, assertValues);
        }
        position++;
      }
    }

    return position;
  }

  public enum UpgradeType {
    ROLLING_UPGRADE,
    ROLLING_UPGRADE_MASTER_ONLY,
    ROLLING_UPGRADE_TSERVER_ONLY,
    FULL_UPGRADE,
    FULL_UPGRADE_MASTER_ONLY,
    FULL_UPGRADE_TSERVER_ONLY
  }

  private int assertGFlagsCommonTasks(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                      int startPosition, UpgradeType type, boolean isFinalStep) {
    int position = startPosition;
    List<TaskType> commonNodeTasks = new ArrayList<>();
    if (type.name().startsWith("ROLLING")) {
      commonNodeTasks.add(TaskType.WaitForServer);
    }

    if (!type.name().endsWith("MASTER_ONLY")) {
      commonNodeTasks.add(TaskType.LoadBalancerStateChange);
    }

    if (isFinalStep) {
      commonNodeTasks.addAll(ImmutableList.of(
          TaskType.UpdateAndPersistGFlags,
          TaskType.UniverseUpdateSucceeded));
    }
    for (int i = 0; i < commonNodeTasks.size(); i++) {
      assertTaskType(subTasksByPosition.get(position), commonNodeTasks.get(i));
      position++;
    }
    return position;
  }

  private int assertSoftwareCommonTasks(Map<Integer, List<TaskInfo>> subTasksByPosition,
                                        int startPosition, UpgradeType type, boolean isFinalStep) {
    int position = startPosition;
    List<TaskType> commonNodeTasks = new ArrayList<>();
    if (type.name().startsWith("ROLLING")) {
      commonNodeTasks.add(TaskType.WaitForServer);
    }

    if (isFinalStep) {
      commonNodeTasks.addAll(ImmutableList.of(
          TaskType.LoadBalancerStateChange,
          TaskType.UpdateSoftwareVersion,
          TaskType.UniverseUpdateSucceeded));
    }
    for (int i = 0; i < commonNodeTasks.size(); i++) {
      assertTaskType(subTasksByPosition.get(position), commonNodeTasks.get(i));
      position++;
    }
    return position;
  }

  private void assertNodeSubTask(List<TaskInfo> subTasks,
                                 Map<String, Object> assertValues) {

    List<String> nodeNames = subTasks.stream()
        .map(t -> t.getTaskDetails().get("nodeName").textValue())
        .collect(Collectors.toList());
    int nodeCount = (int) assertValues.getOrDefault("nodeCount", 1);
    assertEquals(nodeCount, nodeNames.size());
    if (nodeCount == 1) {
      assertEquals(assertValues.get("nodeName"), nodeNames.get(0));
    } else {
      assertTrue(nodeNames.containsAll((List)assertValues.get("nodeNames")));
    }

    List<JsonNode> subTaskDetails = subTasks.stream()
        .map(t -> t.getTaskDetails())
        .collect(Collectors.toList());
    assertValues.forEach((expectedKey, expectedValue) -> {
      if (!ImmutableList.of("nodeName", "nodeNames", "nodeCount").contains(expectedKey)) {
        List<Object> values = subTaskDetails.stream()
            .map(t -> {
              JsonNode data = PROPERTY_KEYS.contains(expectedKey) ? t.get("properties").get(expectedKey): t.get(expectedKey);
              return data.isObject() ? data : data.textValue();
            })
            .collect(Collectors.toList());
        values.forEach((actualValue) -> assertEquals(actualValue, expectedValue));
      }
    });
  }

  private TaskType assertTaskType(List<TaskInfo> tasks, TaskType expectedTaskType) {
    TaskType taskType = tasks.get(0).getTaskType();
    assertEquals(expectedTaskType, taskType);
    return taskType;
  }

  @Test
  public void testSoftwareUpgradeWithSameVersion() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.ybSoftwareVersion = "old-version";

    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.Software);
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    defaultUniverse.refresh();
    assertEquals(4, defaultUniverse.version);
    assertEquals(0, taskInfo.getSubTasks().size());
  }

  @Test
  public void testSoftwareUpgradeWithoutVersion() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.Software);
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    defaultUniverse.refresh();
    assertEquals(4, defaultUniverse.version);
    assertEquals(0, taskInfo.getSubTasks().size());
  }

  @Test
  public void testSoftwareUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.ybSoftwareVersion = "new-version";
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.Software);
    verify(mockNodeManager, times(12)).nodeCommand(any(), any());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    assertTaskType(subTasksByPosition.get(position++), TaskType.LoadBalancerStateChange);
    position = assertSoftwareUpgradeSequence(subTasksByPosition, position, true);
    assertSoftwareCommonTasks(subTasksByPosition, position, UpgradeType.ROLLING_UPGRADE, true);
    assertEquals(19, position);
    assertEquals(100.0, taskInfo.getPercentCompleted(), 0);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testSoftwareNonRollingUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.ybSoftwareVersion = "new-version";
    taskParams.rollingUpgrade = false;

    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.Software);
    ArgumentCaptor<NodeTaskParams> commandParams = ArgumentCaptor.forClass(NodeTaskParams.class);
    verify(mockNodeManager, times(12)).nodeCommand(any(), commandParams.capture());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));
    int position = 0;
    assertTaskType(subTasksByPosition.get(position++), TaskType.LoadBalancerStateChange);
    position = assertSoftwareUpgradeSequence(subTasksByPosition, position, false);
    assertSoftwareCommonTasks(subTasksByPosition, position, UpgradeType.FULL_UPGRADE, true);
    assertEquals(8, position);
    assertEquals(100.0, taskInfo.getPercentCompleted(), 0);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testGFlagsNonRollingUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.masterGFlags = ImmutableMap.of("master-flag", "m1");
    taskParams.tserverGFlags = ImmutableMap.of("tserver-flag", "t1");
    taskParams.rollingUpgrade = false;

    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(18)).nodeCommand(any(), any());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    position = assertGFlagsUpgradeSequence(subTasksByPosition, MASTER, position, false);
    assertTaskType(subTasksByPosition.get(position++), TaskType.LoadBalancerStateChange);
    position = assertGFlagsUpgradeSequence(subTasksByPosition, TSERVER, position, false);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.FULL_UPGRADE, true);
    assertEquals(16, position);
  }

  @Test
  public void testGFlagsNonRollingMasterOnlyUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.masterGFlags = ImmutableMap.of("master-flag", "m1");
    taskParams.rollingUpgrade = false;

    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(9)).nodeCommand(any(), any());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    position = assertGFlagsUpgradeSequence(subTasksByPosition, MASTER, position, false);
    position = assertGFlagsCommonTasks(subTasksByPosition, position,
        UpgradeType.FULL_UPGRADE_MASTER_ONLY, true);
    assertEquals(8, position);
  }

  @Test
  public void testGFlagsNonRollingTServerOnlyUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.tserverGFlags = ImmutableMap.of("tserver-flag", "t1");
    taskParams.rollingUpgrade = false;

    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(9)).nodeCommand(any(), any());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    assertTaskType(subTasksByPosition.get(position++), TaskType.LoadBalancerStateChange);
    position = assertGFlagsUpgradeSequence(subTasksByPosition, TSERVER, position, false);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.FULL_UPGRADE_TSERVER_ONLY, true);

    assertEquals(10, position);
  }

  @Test
  public void testGFlagsUpgradeWithMasterGFlags() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.masterGFlags = ImmutableMap.of("master-flag", "m1");
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(9)).nodeCommand(any(), any());

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    position = assertGFlagsUpgradeSequence(subTasksByPosition, MASTER, position, true);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.ROLLING_UPGRADE_MASTER_ONLY, true);
    assertEquals(18, position);
  }

  @Test
  public void testGFlagsUpgradeWithTServerGFlags() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.tserverGFlags = ImmutableMap.of("tserver-flag", "t1");
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    ArgumentCaptor<NodeTaskParams> commandParams = ArgumentCaptor.forClass(NodeTaskParams.class);
    verify(mockNodeManager, times(9)).nodeCommand(any(), commandParams.capture());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    List<TaskInfo> tasks = subTasksByPosition.get(0);
    TaskType taskType = tasks.get(0).getTaskType();
    assertEquals(1, tasks.size());
    assertEquals(TaskType.LoadBalancerStateChange, taskType);

    int position = 1;
    position = assertGFlagsUpgradeSequence(subTasksByPosition, TSERVER, position, true);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.ROLLING_UPGRADE_TSERVER_ONLY, true);
    assertEquals(20, position);
  }

  @Test
  public void testGFlagsUpgrade() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    taskParams.masterGFlags = ImmutableMap.of("master-flag", "m1");
    taskParams.tserverGFlags = ImmutableMap.of("tserver-flag", "t1");
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(18)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(w -> w.getPosition()));

    int position = 0;
    position = assertGFlagsUpgradeSequence(subTasksByPosition, MASTER, position, true);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.ROLLING_UPGRADE, false);
    position = assertGFlagsUpgradeSequence(subTasksByPosition, TSERVER, position, true);
    position = assertGFlagsCommonTasks(subTasksByPosition, position, UpgradeType.ROLLING_UPGRADE, true);
    assertEquals(36, position);
  }

  @Test
  public void testGFlagsUpgradeWithEmptyFlags() {
    UpgradeUniverse.Params taskParams = new UpgradeUniverse.Params();
    TaskInfo taskInfo = submitTask(taskParams, UpgradeUniverse.UpgradeTaskType.GFlags);
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertEquals(0, subTasks.size());
  }
}
