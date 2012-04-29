/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oodt.cas.workflow.engine;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.workflow.instrepo.WorkflowInstanceRepository;
import org.apache.oodt.cas.workflow.lifecycle.WorkflowLifecycleManager;
import org.apache.oodt.cas.workflow.structs.Workflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;
import org.apache.oodt.cas.workflow.structs.exceptions.EngineException;
import org.apache.oodt.cas.workflow.structs.exceptions.InstanceRepositoryException;
import org.apache.oodt.commons.util.DateConvert;

//JDK imports
import java.net.URL;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

//java.util.concurrent imports
import EDU.oswego.cs.dl.util.concurrent.BoundedBuffer;
import EDU.oswego.cs.dl.util.concurrent.Channel;
import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;
import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;
import EDU.oswego.cs.dl.util.concurrent.ThreadedExecutor;

/**
 * 
 * The ThreadPooling portion of the WorkflowEngine. This class is meant to be an
 * extension point for WorkflowEngines that want to implement ThreadPooling.
 * This WorkflowEngine provides everything needed to manage a ThreadPool using
 * Doug Lea's wonderful java.util.concurrent package that made it into JDK5.
 * 
 * @author mattmann
 * 
 */
public class ThreadPoolWorkflowEngine implements WorkflowEngine, WorkflowStatus {

  /* our thread pool */
  private PooledExecutor pool = null;

  /* our worker thread hash mapping worker threads to workflow instance ids */
  private HashMap workerMap = null;

  /* our log stream */
  private static final Logger LOG = Logger
      .getLogger(ThreadPoolWorkflowEngine.class.getName());

  /* our instance repository */
  private WorkflowInstanceRepository instRep = null;

  /* the URL pointer to the parent Workflow Manager */
  private URL wmgrUrl = null;

  /* how long to wait before checking whether a condition is satisfied. */
  private long conditionWait;

  private ConditionProcessor condProcessor;

  private EngineRunner runner;

  private WorkflowLifecycleManager lifecycleManager;

  /**
   * Default Constructor.
   * 
   * @param instRep
   *          The WorkflowInstanceRepository to be used by this engine.
   * @param queueSize
   *          The size of the queue that the workflow engine should use
   *          (irrelevant if unlimitedQueue is set to true)
   * @param maxPoolSize
   *          The minimum thread pool size.
   * @param minPoolSize
   *          The maximum thread pool size.
   * @param threadKeepAliveTime
   *          The amount of minutes that each thread in the pool should be kept
   *          alive.
   * @param unlimitedQueue
   *          Whether or not to use a queue whose bounds are dictated by the
   *          physical memory of the underlying hardware.
   * @param resUrl
   *          A URL pointer to a resource manager. If this is set Tasks will be
   *          wrapped as Resource Manager {@link Job}s and sent through the
   *          Resource Manager. If this parameter is not set, local execution
   *          (the default) will be used
   */
  public ThreadPoolWorkflowEngine(WorkflowInstanceRepository instRep,
      int queueSize, int maxPoolSize, int minPoolSize,
      long threadKeepAliveTime, boolean unlimitedQueue, URL resUrl) {

    this.instRep = instRep;
    Channel c = null;
    if (unlimitedQueue) {
      c = new LinkedQueue();
    } else {
      c = new BoundedBuffer(queueSize);
    }

    pool = new PooledExecutor(c, maxPoolSize);
    pool.setMinimumPoolSize(minPoolSize);
    pool.setKeepAliveTime(1000 * 60 * threadKeepAliveTime);

    workerMap = new HashMap();

    if (resUrl != null) {
      this.runner = new ResourceRunner(resUrl);
    } else {
      this.runner = new AsynchronousLocalEngineRunner();
    }

    this.conditionWait = Long.getLong(
        "org.apache.oodt.cas.workflow.engine.preConditionWaitTime", 10)
        .longValue();

    this.condProcessor = new ConditionProcessor(lifecycleManager);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#startWorkflow(org.apache
   * .oodt.cas.workflow.structs.Workflow, org.apache.oodt.cas.metadata.Metadata)
   */
  @Override
  public WorkflowInstance startWorkflow(Workflow workflow, Metadata metadata)
      throws EngineException {
    // to start the workflow, we create a default workflow instance
    // populate it
    // persist it
    // add it to the worker map
    // start it

    WorkflowInstance wInst = new WorkflowInstance();
    wInst.setWorkflow(workflow);
    wInst.setCurrentTaskId(((WorkflowTask) workflow.getTasks().get(0))
        .getTaskId());
    wInst.setSharedContext(metadata);
    wInst.setStatus(CREATED);
    persistWorkflowInstance(wInst);

    SequentialProcessor worker = new SequentialProcessor(this.lifecycleManager);
    workerMap.put(wInst.getId(), worker);

    wInst.setStatus(QUEUED);
    persistWorkflowInstance(wInst);

    /*
     * try { pool.execute(new ThreadedExecutor(worker, this.condProcessor)); }
     * catch (InterruptedException e) { throw new EngineException(e); }
     */

    return wInst;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#stopWorkflow(java.lang
   * .String)
   */
  @Override
  public void stopWorkflow(String workflowInstId) {
    // TODO Auto-generated method stub

  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#pauseWorkflowInstance
   * (java.lang.String)
   */
  @Override
  public void pauseWorkflowInstance(String workflowInstId) {
    // TODO Auto-generated method stub

  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#resumeWorkflowInstance
   * (java.lang.String)
   */
  @Override
  public void resumeWorkflowInstance(String workflowInstId) {
    // TODO Auto-generated method stub

  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getInstanceRepository()
   */
  @Override
  public WorkflowInstanceRepository getInstanceRepository() {
    // TODO Auto-generated method stub
    return null;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#updateMetadata(java.
   * lang.String, org.apache.oodt.cas.metadata.Metadata)
   */
  @Override
  public boolean updateMetadata(String workflowInstId, Metadata met) {
    // TODO Auto-generated method stub
    return false;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#setWorkflowManagerUrl
   * (java.net.URL)
   */
  @Override
  public void setWorkflowManagerUrl(URL url) {
    // TODO Auto-generated method stub

  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getWallClockMinutes(
   * java.lang.String)
   */
  @Override
  public double getWallClockMinutes(String workflowInstId) {
    // TODO Auto-generated method stub
    return 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.engine.WorkflowEngine#
   * getCurrentTaskWallClockMinutes(java.lang.String)
   */
  @Override
  public double getCurrentTaskWallClockMinutes(String workflowInstId) {
    // TODO Auto-generated method stub
    return 0;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.WorkflowEngine#getWorkflowInstanceMetadata
   * (java.lang.String)
   */
  @Override
  public Metadata getWorkflowInstanceMetadata(String workflowInstId) {
    // TODO Auto-generated method stub
    return null;
  }

  protected static double getWallClockMinutes(WorkflowInstance inst) {
    if (inst == null) {
      return 0.0;
    }

    Date currentDateOrStopTime = (inst.getEndDateTimeIsoStr() != null
        && !inst.getEndDateTimeIsoStr().equals("") && !inst
        .getEndDateTimeIsoStr().equals("null")) ? safeDateConvert(inst
        .getEndDateTimeIsoStr()) : new Date();

    Date workflowStartDateTime = null;

    if (inst.getStartDateTimeIsoStr() == null
        || (inst.getStartDateTimeIsoStr() != null && (inst
            .getStartDateTimeIsoStr().equals("") || inst
            .getStartDateTimeIsoStr().equals("null")))) {
      return 0.0;
    }

    try {
      workflowStartDateTime = DateConvert.isoParse(inst
          .getStartDateTimeIsoStr());
    } catch (ParseException e) {
      return 0.0;
    }

    long diffMs = currentDateOrStopTime.getTime()
        - workflowStartDateTime.getTime();
    double diffSecs = (diffMs * 1.0 / 1000.0);
    double diffMins = diffSecs / 60.0;
    return diffMins;

  }

  protected static double getCurrentTaskWallClockMinutes(WorkflowInstance inst) {
    if (inst == null) {
      return 0.0;
    }

    Date currentDateOrStopTime = (inst.getCurrentTaskEndDateTimeIsoStr() != null
        && !inst.getCurrentTaskEndDateTimeIsoStr().equals("") && !inst
        .getCurrentTaskEndDateTimeIsoStr().equals("null")) ? safeDateConvert(inst
        .getCurrentTaskEndDateTimeIsoStr()) : new Date();

    Date workflowTaskStartDateTime = null;

    if (inst.getCurrentTaskStartDateTimeIsoStr() == null
        || (inst.getCurrentTaskStartDateTimeIsoStr() != null && (inst
            .getCurrentTaskStartDateTimeIsoStr().equals("") || inst
            .getCurrentTaskStartDateTimeIsoStr().equals("null")))) {
      return 0.0;
    }

    try {
      workflowTaskStartDateTime = DateConvert.isoParse(inst
          .getCurrentTaskStartDateTimeIsoStr());
    } catch (ParseException e) {
      return 0.0;
    }

    // should never be in this state, so return 0
    if (workflowTaskStartDateTime.after(currentDateOrStopTime)) {
      LOG.log(
          Level.WARNING,
          "Start date time: ["
              + DateConvert.isoFormat(workflowTaskStartDateTime)
              + " of workflow inst [" + inst.getId() + "] is AFTER "
              + "End date time: ["
              + DateConvert.isoFormat(currentDateOrStopTime)
              + "] of workflow inst.");
      return 0.0;
    }

    long diffMs = currentDateOrStopTime.getTime()
        - workflowTaskStartDateTime.getTime();
    double diffSecs = (diffMs * 1.0 / 1000.0);
    double diffMins = diffSecs / 60.0;
    return diffMins;
  }

  private static Date safeDateConvert(String isoTimeStr) {
    try {
      return DateConvert.isoParse(isoTimeStr);
    } catch (Exception ignore) {
      ignore.printStackTrace();
      return null;
    }
  }

  private synchronized void persistWorkflowInstance(WorkflowInstance wInst)
      throws EngineException {

    try {
      if (wInst.getId() == null
          || (wInst.getId() != null && wInst.getId().equals(""))) {
        // we have to persist it by adding it
        // rather than updating it
        instRep.addWorkflowInstance(wInst);

      } else {
        // persist by update
        instRep.updateWorkflowInstance(wInst);
      }
    } catch (InstanceRepositoryException e) {
      e.printStackTrace();
      throw new EngineException(e.getMessage());
    }

  }

  // FIXME: add back in the ThreadPoolWorkflowEngine implementation,
  // after the sub-modules are fixed.

}
