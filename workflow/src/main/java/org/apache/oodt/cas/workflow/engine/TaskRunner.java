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
import java.util.logging.Level;
import java.util.logging.Logger;

//OODT imports
import org.apache.oodt.cas.workflow.structs.ParentChildWorkflow;
import org.apache.oodt.cas.workflow.structs.WorkflowInstance;
import org.apache.oodt.cas.workflow.structs.WorkflowTask;

/**
 * 
 * Implements the TaskRunner framework. Acts as a thread that works with the
 * TaskQuerier to take the next sorted (aka ones that have been sorted with the
 * Workflow PrioritySorter) task and then leverage the Engine's Runner to
 * execute the task.
 * 
 * The TaskRunner thread first pops a task off the list using
 * {@link TaskQuerier#getNext()} and then so long as the thread's
 * {@link #runner} has open slots as returned by
 * {@link EngineRunner#hasOpenSlots(WorkflowTask)}, and {@link #isPause()} is
 * false and {@link #isRunning()} is true, then the task is handed off to the
 * runner for execution.
 * 
 * The TaskRunner thread can be paused during which time it waits
 * {@link #waitSeconds} seconds, wakes up to see if it's unpaused, and then goes
 * back to sleep if not, otherwise, resumes executing if it was unpaused.
 * 
 * @since Apache OODT 0.5
 * 
 * @author mattmann
 * @author bfoster
 * @version $Revision$
 * 
 */
public class TaskRunner implements Runnable {

  private boolean running;

  private boolean pause;

  private TaskQuerier taskQuerier;

  private EngineRunner runner;

  private int waitSeconds;

  private static final Logger LOG = Logger
      .getLogger(TaskRunner.class.getName());

  public TaskRunner(TaskQuerier taskQuerier, EngineRunner runner,
      int waitSeconds) {
    this.running = true;
    this.pause = false;
    this.taskQuerier = taskQuerier;
    this.runner = runner;
    this.waitSeconds = waitSeconds;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Runnable#run()
   */
  @Override
  public void run() {
    WorkflowTask nextTask = null;
    TaskProcessor nextTaskProcessor = null;

    while (running) {
      try {
        if (nextTaskProcessor == null){
          nextTaskProcessor = taskQuerier.getNext();          
          nextTask = extractTaskFromProcessor(nextTaskProcessor);
        }
        while (running && !pause && nextTask != null
            && runner.hasOpenSlots(nextTask)) {
          // TODO: set Workflow met here?
          runner.execute(nextTask, nextTaskProcessor.getDynamicMetadata());
          nextTaskProcessor = taskQuerier.getNext();
          nextTask = extractTaskFromProcessor(nextTaskProcessor);

          // take a breather
          try{
            Thread.currentThread().sleep(1000); //FIXME: make this configurable
          }
          catch (Exception ignore) {}
        }
      } catch (Exception e) {
        LOG.log(
            Level.SEVERE,
            "Engine failed while submitting jobs to its runner : "
                + e.getMessage(), e);
        if (nextTask != null) {
          nextTaskProcessor.setState(nextTaskProcessor
              .getLifecycleManager()
              .getDefaultLifecycle()
              .createState("Failure", "done",
                  "Failed while submitting job to Runner : " + e.getMessage()));
          nextTask = null;
          nextTaskProcessor = null;
        }
      }

      try {
        synchronized (this) {
          do {
            this.wait(waitSeconds * 1000);
          } while (pause);
        }
      } catch (Exception ignore) {
      }
    }
  }

  /**
   * @return the waitSeconds
   */
  public int getWaitSeconds() {
    return waitSeconds;
  }

  /**
   * @param waitSeconds
   *          the waitSeconds to set
   */
  public void setWaitSeconds(int waitSeconds) {
    this.waitSeconds = waitSeconds;
  }

  /**
   * @return the running
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * @param running
   *          the running to set
   */
  public void setRunning(boolean running) {
    this.running = running;
  }

  /**
   * @return the pause
   */
  public boolean isPause() {
    return pause;
  }

  /**
   * @param pause
   *          the pause to set
   */
  public void setPause(boolean pause) {
    this.pause = pause;
  }

  protected WorkflowTask extractTaskFromProcessor(TaskProcessor taskProcessor) {
    WorkflowInstance inst = taskProcessor.getWorkflowInstance();
    ParentChildWorkflow workflow = inst.getParentChildWorkflow();
    String taskId = inst.getCurrentTaskId();
    for (WorkflowTask task : workflow.getTasks()) {
      if (task.getTaskId().equals(taskId)) {
        return task;
      }
    }

    return null;
  }

}