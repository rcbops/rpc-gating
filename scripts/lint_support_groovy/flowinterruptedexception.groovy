// Dummy FlowInterruptedException only used in lint jobs.

package org.jenkinsci.plugins.workflow.steps

import java.io.IOException;

public class FlowInterruptedException extends IOException{
  public FlowInterruptedException() {
  }
}
