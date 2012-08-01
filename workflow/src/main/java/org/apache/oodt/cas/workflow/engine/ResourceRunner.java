/**
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

//JDK imports
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.Job;
import org.apache.oodt.cas.resource.structs.exceptions.JobExecutionException;
import org.apache.oodt.cas.resource.system.XmlRpcResourceManagerClient;
import org.apache.oodt.cas.workflow.metadata.CoreMetKeys;
import org.apache.oodt.cas.workflow.structs.TaskJobInput;
import org.apache.oodt.cas.workflow.structs.WorkflowStatus;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * 
 * Submits a {@link WorkflowTask} to the Resource Manager.
 * 
 * @author mattmann
 * @version $Revision$
 * 
 */
public class ResourceRunner extends EngineRunner implements CoreMetKeys,
    WorkflowStatus {

  private static final Logger LOG = Logger.getLogger(ResourceRunner.class
      .getName());

  protected static final String DEFAULT_QUEUE_NAME = "high";

  protected XmlRpcResourceManagerClient rClient;

  private String currentJobId;

  public ResourceRunner(URL resUrl) {
    this.rClient = new XmlRpcResourceManagerClient(resUrl);
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.apache.oodt.cas.workflow.engine.EngineRunner#execute(org.apache.oodt
   * .cas.workflow.structs.WorkflowTask, org.apache.oodt.cas.metadata.Metadata)
   */
  @Override
  public void execute(WorkflowTask workflowTask, Metadata dynMetadata)
      throws Exception {
    Job workflowTaskJob = new Job();
    workflowTaskJob.setName(workflowTask.getTaskId());
    workflowTaskJob
        .setJobInstanceClassName("org.apache.oodt.cas.workflow.structs.TaskJob");
    workflowTaskJob
        .setJobInputClassName("org.apache.oodt.cas.workflow.structs.TaskJobInput");
    workflowTaskJob.setLoadValue(new Integer(2));
    workflowTaskJob.setQueueName(workflowTask.getTaskConfig().getProperty(
        QUEUE_NAME) != null ? workflowTask.getTaskConfig().getProperty(
        QUEUE_NAME) : DEFAULT_QUEUE_NAME);

    if (workflowTask.getTaskConfig().getProperty(TASK_LOAD) != null) {
      workflowTaskJob.setLoadValue(Integer.valueOf(workflowTask.getTaskConfig()
          .getProperty(TASK_LOAD)));
    }

    TaskJobInput in = new TaskJobInput();
    in.setDynMetadata(dynMetadata);
    in.setTaskConfig(workflowTask.getTaskConfig());
    in.setWorkflowTaskInstanceClassName(workflowTask.getTaskInstanceClassName());

    try {
      this.currentJobId = rClient.submitJob(workflowTaskJob, in);
    } catch (JobExecutionException e) {
      LOG.log(Level.WARNING,
          "Job execution exception using resource manager to execute job: Message: "
              + e.getMessage());
    }

  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.oodt.cas.workflow.engine.EngineRunner#shutdown()
   */
  @Override
  public void shutdown() throws Exception {
    // TODO Auto-generated method stub

  }
  

  /* (non-Javadoc)
   * @see org.apache.oodt.cas.workflow.engine.EngineRunner#hasOpenSlots(org.apache.oodt.cas.workflow.structs.WorkflowTask)
   */
  @Override
  public boolean hasOpenSlots(WorkflowTask workflowTask) throws Exception {
    // TODO Auto-generated method stub
    return false;
  }
  

  protected boolean safeCheckJobComplete(String jobId) {
    try {
      return rClient.isJobComplete(jobId);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Exception checking completion status for job: ["
          + jobId + "]: Messsage: " + e.getMessage());
      return false;
    }
  }

  protected boolean stopJob(String jobId) {
    if (this.rClient != null && this.currentJobId != null) {
      if (!this.rClient.killJob(this.currentJobId)) {
        LOG.log(Level.WARNING, "Attempt to kill " + "current resmgr job: ["
            + this.currentJobId + "]: failed");
        return false;
      } else
        return true;
    } else
      return false;
  }

}